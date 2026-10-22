package com.law.backend.service;

import com.law.backend.attachment.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 附件模块的外部 interface：会话身份贯穿上传、状态、上下文、来源和删除。 */
@Slf4j
@Service
@EnableConfigurationProperties(FileProperties.class)
public class ChatFileService {
    public record ImgMeta(int idx, String location, String caption) {}
    public record UploadedFile(String fileId, String name, int chars, boolean truncated, String kind,
                               List<ImgMeta> images, String status, int totalPages, int processedPages,
                               List<Integer> failedPages, List<String> warnings, String error, long expiresAt) {}
    public record StoredImage(int idx, String mime, byte[] bytes, String location, String caption) {}
    public record Turn(List<AttachmentRepository.FileRecord> files, List<StoredImage> images, String memorySuffix) {}
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf", "docx", "png", "jpg", "jpeg", "webp");
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "webp");
    private final FileProperties properties;
    private final PrivateAttachmentStorage storage;
    private final AttachmentRepository repository;
    private final AttachmentParser parser;
    private final AttachmentChunker chunker;
    private final AttachmentEmbedding embedding;
    private final AttachmentRetrieval retrieval;
    private final AttachmentReview review;
    private final SessionOwnershipService ownership;
    private final ThreadPoolExecutor worker;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ChatFileService(FileProperties properties, PrivateAttachmentStorage storage, AttachmentRepository repository,
                           AttachmentParser parser, AttachmentChunker chunker, AttachmentEmbedding embedding,
                           AttachmentRetrieval retrieval, AttachmentReview review, SessionOwnershipService ownership) {
        this.properties = properties; this.storage = storage; this.repository = repository; this.parser = parser;
        this.chunker = chunker; this.embedding = embedding; this.retrieval = retrieval; this.review = review; this.ownership = ownership;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()), task -> {
                    Thread thread = new Thread(task, "attachment-parser"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    private AttachmentRepository.Scope scope(String sessionId, SessionOwnershipService.Owner owner, boolean create) {
        if (SessionOwnershipService.ANON.equals(owner.key())) throw new SessionAccessDeniedException();
        if (create) ownership.register(sessionId, owner); else { ownership.claim(sessionId, owner); ownership.require(sessionId, owner); }
        var scope = new AttachmentRepository.Scope(sessionId, owner.key(), System.currentTimeMillis());
        repository.migrateGuest(scope, owner.guestKey());
        return scope;
    }
    /** 限长、限量的流式写盘，不 join 整个 multipart 到 JVM 堆。 */
    public Mono<UploadedFile> upload(String sessionId, SessionOwnershipService.Owner owner, FilePart part) {
        return Mono.defer(() -> {
            String name = safeName(part.filename());
            String extension = extension(name);
            if (!EXTENSIONS.contains(extension)) return Mono.error(new IllegalArgumentException("支持 txt/md/pdf/docx/png/jpg/webp 格式"));
            long limit = IMAGES.contains(extension) ? properties.getMaxImageBytes() : properties.getMaxFileBytes();
            String id = storage.newId();
            AtomicLong bytes = new AtomicLong();
            return Mono.fromCallable(() -> {
                        scope(sessionId, owner, true); storage.create(id); return id;
                    }).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ignored -> DataBufferUtils.write(part.content().<DataBuffer>handle((buffer, sink) -> {
                        if (bytes.addAndGet(buffer.readableByteCount()) > limit) {
                            DataBufferUtils.release(buffer);
                            sink.error(new IllegalArgumentException("附件超过 " + limit / 1024 / 1024 + "MB 限制"));
                        } else sink.next(buffer);
                    }).doOnDiscard(DataBuffer.class, DataBufferUtils::release), storage.path(id), StandardOpenOption.WRITE))
                    .then(Mono.fromCallable(() -> {
                        if (bytes.get() == 0) throw new IllegalArgumentException("不能上传空文件");
                        var scope = scope(sessionId, owner, false);
                        repository.create(scope, id, name, extension, bytes.get(), scope.now() + TimeUnit.HOURS.toMillis(properties.getTtlHours()),
                                properties.getMaxFilesPerSession(), AttachmentParser.VERSION, AttachmentChunker.VERSION, properties.getEmbeddingVersion());
                        var result = uploaded(repository.require(scope, id));
                        enqueue(id, extension); return result;
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .onErrorResume(error -> Mono.fromRunnable(() -> discard(id, sessionId, owner)).subscribeOn(Schedulers.boundedElastic()).then(Mono.error(error)))
                    .doFinally(signal -> { if (signal == reactor.core.publisher.SignalType.CANCEL) Schedulers.boundedElastic().schedule(() -> discard(id, sessionId, owner)); });
        });
    }
    private void enqueue(String id, String extension) {
        active.add(id);
        try { worker.execute(() -> { try { process(id, extension); } finally { active.remove(id); } }); }
        catch (RejectedExecutionException e) { active.remove(id); repository.fail(id, "解析队列已满，请稍后重试", System.currentTimeMillis()); throw new IllegalArgumentException("解析队列已满，请稍后重试"); }
    }
    private void process(String id, String extension) {
        if (!repository.start(id, System.currentTimeMillis())) return;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getProcessingTimeoutSeconds());
            var parsed = parser.parse(storage.path(id), extension);
            if (parsed.text().isBlank() && !IMAGES.contains(extension) && parsed.failedPages().isEmpty())
                throw new IllegalArgumentException("未提取出正文；请检查扫描件或文件内容");
            var chunks = chunker.split(parsed.text(), parsed.sources());
            List<float[]> vectors = new ArrayList<>();
            List<String> warnings = new ArrayList<>(parsed.warnings());
            try {
                for (int i = 0; i < chunks.children().size(); i += 16) {
                    if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw new IllegalStateException();
                    var batch = chunks.children().subList(i, Math.min(chunks.children().size(), i + 16));
                    var result = embedding.batch(batch.stream().map(c -> c.source() + "\n" + c.text()).toList());
                    if (result.size() != batch.size()) throw new IllegalStateException();
                    result.forEach(AttachmentRepository::vectorLiteral);
                    vectors.addAll(result);
                }
            } catch (Exception e) { vectors.clear(); warnings.add("向量化暂未完成，当前可用关键词检索；可重试附件处理"); }
            repository.complete(id, parsed, chunks, vectors, warnings, System.currentTimeMillis());
        } catch (Exception e) {
            String message = e instanceof IllegalArgumentException ? e.getMessage() : "文件解析失败，请检查格式、加密状态或拆分后重试";
            repository.fail(id, message == null ? "附件处理失败" : message, System.currentTimeMillis());
            log.warn("附件处理失败：fileId={}, type={}", id, e.getClass().getSimpleName());
        }
    }
    public List<UploadedFile> list(String sessionId, SessionOwnershipService.Owner owner) {
        return repository.list(scope(sessionId, owner, false)).stream().map(this::uploaded).toList();
    }
    public UploadedFile retry(String sessionId, SessionOwnershipService.Owner owner, String id) {
        var scope = scope(sessionId, owner, false);
        var file = repository.require(scope, id);
        if (!repository.retry(scope, id)) throw new IllegalArgumentException("附件正在处理或无需重试");
        enqueue(id, file.extension());
        return uploaded(repository.require(scope, id));
    }
    public void delete(String sessionId, SessionOwnershipService.Owner owner, String id) {
        var scope = scope(sessionId, owner, false); repository.require(scope, id); repository.expire(scope, id);
        purge(id, System.currentTimeMillis());
    }
    public void deleteSession(String sessionId, SessionOwnershipService.Owner owner) {
        var scope = scope(sessionId, owner, false);
        var files = repository.list(scope); repository.expireSession(scope);
        for (var file : files) purge(file.fileId(), System.currentTimeMillis());
    }
    public String source(String sessionId, SessionOwnershipService.Owner owner, String id, int parentId) {
        var scope = scope(sessionId, owner, false); repository.require(scope, id);
        return repository.parents(scope, List.of(id)).stream().filter(p -> p.parentId() == parentId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("引用原文不存在或已过期")).text();
    }
    public Turn turn(String sessionId, SessionOwnershipService.Owner owner, List<String> ids, String question) {
        if (SessionOwnershipService.ANON.equals(owner.key())) {
            if (ids != null && !ids.isEmpty()) throw new SessionAccessDeniedException();
            return new Turn(List.of(), List.of(), "");
        }
        ownership.register(sessionId, owner);
        var scope = scope(sessionId, owner, false);
        if (ids != null && ids.size() > properties.getMaxFilesPerSession()) throw new IllegalArgumentException("本轮附件过多");
        List<AttachmentRepository.FileRecord> files = ids == null || ids.isEmpty() ? repository.list(scope)
                : ids.stream().distinct().map(id -> repository.require(scope, id)).toList();
        for (var file : files) if (!Set.of("READY", "PARTIAL").contains(file.status()))
            throw new IllegalArgumentException("附件尚未就绪或处理失败，请查看附件状态");
        List<StoredImage> images = new ArrayList<>();
        for (var file : files) if (IMAGES.contains(file.extension())) {
            try {
                if (ids != null && ids.contains(file.fileId()) || question.contains("图"))
                    images.add(new StoredImage(images.size() + 1, AttachmentParser.mime(file.extension()), Files.readAllBytes(storage.path(file.fileId())), "用户上传图片", ""));
            } catch (Exception e) { throw new IllegalArgumentException("附件原图不可用，请重新上传"); }
        }
        if (images.size() > properties.getMaxImagesPerTurn()) throw new IllegalArgumentException("本轮图片过多");
        String suffix = files.isEmpty() ? "" : "\n[会话附件：" + String.join("、", files.stream().map(AttachmentRepository.FileRecord::name).toList()) + "；有效期24小时]";
        return new Turn(files, images, suffix);
    }
    public AttachmentContext context(String sessionId, SessionOwnershipService.Owner owner, Turn turn, String question,
                                      int budget, boolean fullReview, Consumer<String> stage, BooleanSupplier cancelled) {
        if (turn.files().isEmpty()) {
            if (fullReview) throw new IllegalArgumentException("请先上传待审查文档");
            return AttachmentContext.empty();
        }
        var scope = scope(sessionId, owner, false);
        turn.files().forEach(file -> repository.require(scope, file.fileId()));
        return fullReview ? review.review(scope, turn.files(), question, budget, stage, cancelled)
                : retrieval.retrieve(scope, turn.files(), question, budget);
    }
    private UploadedFile uploaded(AttachmentRepository.FileRecord file) {
        boolean image = IMAGES.contains(file.extension());
        return new UploadedFile(file.fileId(), file.name(), file.chars(), false, image ? "image" : file.kind(),
                image ? List.of(new ImgMeta(1, "用户上传", "")) : List.of(), file.status(), file.totalPages(), file.processedPages(), file.failedPages(), file.warnings(), file.error(), file.expiresAt());
    }
    private void discard(String id, String session, SessionOwnershipService.Owner owner) {
        try { repository.expire(new AttachmentRepository.Scope(session, owner.key(), System.currentTimeMillis()), id); }
        catch (Exception e) { log.warn("附件上传元数据回收待重试：fileId={}", id); }
        try { storage.delete(id); repository.purge(id, System.currentTimeMillis()); }
        catch (Exception e) { log.warn("附件上传原件回收待重试：fileId={}", id); }
    }
    private void purge(String id, long now) {
        try { storage.delete(id); repository.purge(id, now); }
        catch (Exception e) { log.warn("附件过期回收待重试：fileId={}", id); }
    }
    @Scheduled(fixedDelayString = "${legal.ai.files.cleanup-delay-ms:60000}", initialDelay = 60000)
    public void cleanup() {
        try {
            long now = System.currentTimeMillis();
            repository.recoverInterrupted(now - TimeUnit.MINUTES.toMillis(30), now);
            repository.expired(now).forEach(id -> purge(id, now));
            for (String id : storage.orphanCandidates(now - TimeUnit.HOURS.toMillis(1)))
                if (!active.contains(id) && !repository.exists(id)) storage.delete(id);
        } catch (Exception e) { log.warn("附件维护未完成，将在下次重试"); }
    }
    private static String extension(String name) { int index = name.lastIndexOf('.'); return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT); }
    private static String safeName(String raw) {
        String name = raw == null ? "附件" : raw.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.length() > 180) throw new IllegalArgumentException("附件文件名无效或过长");
        return name;
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
