package com.law.backend.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Task checkpoints on the application's private, persistent volume. One backend instance owns the volume. */
@Component
final class ContractScanStore {
    private final Path root;
    private final ObjectMapper json;

    ContractScanStore(FileProperties files, ObjectMapper json) throws IOException {
        this.json = json;
        root = Path.of(files.getStorageDir()).toAbsolutePath().resolve("contract-scans");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("合同扫描目录不能是符号链接");
        privateDirectory(root);
    }

    enum State { DRAFT, QUEUED, SCANNING, REVIEWING, PAUSED, READY, PARTIAL, FAILED, DELETED }

    record Input(String id, String name, String format, long size, boolean optimized, List<String> chunkHashes) {
        boolean pdf() { return "pdf".equals(format); }
    }

    record Repair(int page, Input input) { }
    record Evidence(int page, String quote) { }
    record Risk(String severity, String title, String suggestion, List<Evidence> evidence) { }
    record Term(String type, Evidence evidence) { }
    record ScanPage(String text, String warning, boolean scanned, boolean ocr) { }
    record PageReview(List<Risk> risks, List<Term> terms) { }
    record CrossReview(List<Risk> risks, boolean complete) { }

    static final class Job {
        public String id;
        public long owner;
        public long createdAt;
        public long expiresAt;
        public State state;
        public String stance;
        public String revision;
        public List<Input> inputs = new ArrayList<>();
        public int pages;
        public String issue = "";
        public Repair repair;
    }

    String id() { return UUID.randomUUID().toString().replace("-", ""); }

    Path jobPath(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("合同任务不存在");
        return root.resolve(id);
    }

    long usableSpace() throws IOException { return Files.getFileStore(root).getUsableSpace(); }

    List<Path> directories() throws IOException {
        try (var entries = Files.list(root)) {
            return entries.filter(p -> p.getFileName().toString().matches("[a-f0-9]{32}"))
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    List<Job> jobs() throws IOException {
        List<Job> jobs = new ArrayList<>();
        for (Path directory : directories()) {
            Job job = read(directory.resolve("job.json"), Job.class);
            if (job != null) jobs.add(job);
        }
        return jobs;
    }

    Job job(String id) throws IOException { return read(jobPath(id).resolve("job.json"), Job.class); }

    void create(Job job) throws IOException {
        Path directory = Files.createDirectory(jobPath(job.id));
        privateDirectory(directory);
        save(job);
    }

    void save(Job job) throws IOException { write(jobPath(job.id).resolve("job.json"), job); }

    Path asset(Job job, Input input) { return jobPath(job.id).resolve("input-" + input.id()); }
    Path source(Job job, int page) { return jobPath(job.id).resolve("page-" + page + ".json"); }
    Path review(Job job, int page) { return jobPath(job.id).resolve("review-" + page + ".json"); }
    Path cross(Job job) { return jobPath(job.id).resolve("cross.json"); }
    Path usage() { return root.resolve("usage.json"); }

    long received(Job job, Input input) throws IOException {
        Path path = asset(job, input);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
    }

    <T> T read(Path path, Class<T> type) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? json.readValue(path.toFile(), type) : null;
    }

    void write(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            json.writeValue(temporary.toFile(), value);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void removeUnreferencedAssets(Job job) throws IOException {
        var live = new java.util.HashSet<>(job.inputs.stream().map(Input::id).toList());
        if (job.repair != null) live.add(job.repair.input().id());
        try (var paths = Files.list(jobPath(job.id))) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.matches("input-[a-f0-9]{32}") && !live.contains(name.substring(6))) Files.deleteIfExists(path);
            }
        }
    }

    void remove(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void privateDirectory(Path directory) throws IOException {
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* The volume inherits the Windows user ACL. */ }
    }
}
