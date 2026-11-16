package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 上传名不参与磁盘路径；目录不映射到 Web 静态资源。 */
@Component
public class PrivateAttachmentStorage {
    private final Path root;
    private final FileProperties properties;
    private final Map<String, Long> uploads = new HashMap<>();
    public PrivateAttachmentStorage(FileProperties properties) throws IOException {
        this.properties = properties;
        root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("附件目录不得是符号链接");
        try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* Windows 使用当前进程用户 ACL。 */ }
    }
    public String newId() { return UUID.randomUUID().toString().replace("-", ""); }
    public Path path(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("附件标识无效");
        return root.resolve(id).normalize();
    }
    public Path create(String id) throws IOException {
        Path path = Files.createFile(path(id));
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        return path;
    }
    /** 预留单次上传的最大字节，计入其他正在写的原件和重启前留下的文件。 */
    public synchronized void reserve(String id, long bytes) throws IOException {
        if (uploads.size() >= properties.getMaxConcurrentUploads())
            throw new IllegalArgumentException("附件上传繁忙，请稍后重试");
        long reservedBytes = bytes;
        for (long reserved : uploads.values()) reservedBytes += reserved;
        long available = properties.getMaxPrivateStorageBytes() - reservedBytes;
        try (var entries = Files.list(root)) {
            var iterator = entries.iterator();
            while (available >= 0 && iterator.hasNext()) {
                Path entry = iterator.next();
                String name = entry.getFileName().toString();
                if (!name.matches("[a-f0-9]{32}") || uploads.containsKey(name)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                try { available -= Files.size(entry); }
                catch (java.nio.file.NoSuchFileException ignored) { /* 同时发生的过期回收。 */ }
            }
        }
        if (available < 0 || Files.getFileStore(root).getUsableSpace() < reservedBytes)
            throw new IllegalArgumentException("附件存储空间暂不足，请稍后重试");
        create(id);
        uploads.put(id, bytes);
    }
    public synchronized void release(String id) { uploads.remove(id); }
    public void delete(String id) throws IOException { Files.deleteIfExists(path(id)); }
    public java.util.List<String> orphanCandidates(long before) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{32}"))
                    .filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() < before; } catch (IOException e) { return false; } })
                    .map(p -> p.getFileName().toString()).toList();
        }
    }
}
