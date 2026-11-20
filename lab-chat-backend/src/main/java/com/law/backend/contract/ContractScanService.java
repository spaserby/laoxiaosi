package com.law.backend.contract;

import com.law.backend.model.ModelRouteProperties;
import com.law.backend.service.FileProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.law.backend.contract.ContractScanStore.*;

@Service
public final class ContractScanService {
    public static final int CHUNK_BYTES = 2 * 1024 * 1024;
    private static final long DISK_MARGIN = 64L * 1024 * 1024;
    private static final Set<String> STANCES = Set.of("中立", "劳动者", "用人单位", "承租方", "出租方", "甲方", "乙方");
    private static final Set<State> BUSY = Set.of(State.DRAFT, State.QUEUED, State.SCANNING, State.REVIEWING, State.PAUSED);
    private static final Set<State> PROCESSING = Set.of(State.QUEUED, State.SCANNING, State.REVIEWING);
    private static final Set<State> FINISHED = Set.of(State.PARTIAL, State.READY, State.FAILED);
    private final ContractScanStore store;
    private final ContractScanDocuments documents;
    private final ContractScanProperties config;
    private final Policy policy;
    private final java.util.concurrent.Semaphore uploads;
    private final java.util.concurrent.Semaphore previews = new java.util.concurrent.Semaphore(2);

    ContractScanService(ContractScanStore store, ContractScanDocuments documents, ContractScanProperties config,
                        ModelRouteProperties routes, FileProperties files) {
        this.store = store;
        this.documents = documents;
        this.config = config;
        uploads = new java.util.concurrent.Semaphore(Math.max(1, config.getMaxUploads()));
        var legal = routes.resolve("legal");
        var vision = files.getVlProvider();
        String ocrRecipient = label(config.getOcrRecipient(), vision);
        String reviewRecipient = label(config.getReviewRecipient(), legal[0]);
        String revision = digest(List.of(vision, files.getVlModel(), routes.getProviders().get(vision).getBaseUrl(),
                legal[0], legal[1], routes.getProviders().get(legal[0]).getBaseUrl(), ocrRecipient, reviewRecipient).toString());
        policy = new Policy(config.isEnabled() && files.isOcrEnabled(), revision, ocrRecipient, reviewRecipient,
                config.getMaxPhotos(), config.getMaxPdfPages(), config.getMaxBytes(), config.getMaxPhotoBytes(),
                config.getMaxPhotoPixels(), CHUNK_BYTES, config.getRetentionHours());
    }

    public record Policy(boolean enabled, String revision, String ocrRecipient, String reviewRecipient, int maxPhotos,
                         int maxPdfPages, long maxBytes, long maxPhotoBytes, long maxPhotoPixels, int chunkBytes, int retentionHours) { }
    public record UploadSpec(String name, String format, long size, boolean optimized, List<String> chunkHashes) {
        public UploadSpec(String name, String format, long size, boolean optimized) { this(name, format, size, optimized, null); }
    }
    public record CreateRequest(List<UploadSpec> files, String stance, String revision) { }
    public record Consent(String revision) { }
    public record Replacement(UploadSpec file, String revision) { }
    public record Offset(long received) { }
    public record FileView(String id, String name, String format, long size, long received, boolean optimized) { }
    public record Summary(String id, String title, String status, long createdAt, long expiresAt, int totalPages) { }
    public record PageView(int page, boolean scanned, boolean reviewed, String warning) { }
    public record Detail(Summary summary, String stance, String issue, List<FileView> files, List<PageView> pages,
                         List<Risk> risks, Boolean crossChecked, Integer replacementPage, FileView replacement) { }
    public record Binary(Path path, byte[] bytes, String mime, String name, long size) { }
    record Usage(String date, int ocr, int review) { }

    public static final class Problem extends RuntimeException {
        private final HttpStatus status;
        Problem(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }

    public Policy policy() { return policy; }
    public boolean beginUpload() { return uploads.tryAcquire(); }
    public void endUpload() { uploads.release(); }

    public List<Summary> list(long owner) throws IOException {
        synchronized (store) {
            long now = System.currentTimeMillis();
            return store.jobs().stream().filter(j -> j.owner == owner && visible(j, now))
                    .sorted(Comparator.comparingLong((Job j) -> j.createdAt).reversed()).map(this::summary).toList();
        }
    }

    public Detail detail(long owner, String jobId) throws IOException {
        synchronized (store) { return detail(require(owner, jobId)); }
    }

    public Detail create(long owner, CreateRequest request) throws IOException {
        checkConsent(request.revision());
        if (request.files() == null || request.files().isEmpty() || request.files().size() > policy.maxPhotos())
            throw bad("请选择一份 PDF 或最多 " + policy.maxPhotos() + " 张照片");
        String stance = request.stance() == null ? "中立" : request.stance();
        if (!STANCES.contains(stance)) throw bad("请选择有效的审查立场");
        Job job = new Job();
        job.id = store.id();
        job.owner = owner;
        job.createdAt = System.currentTimeMillis();
        job.expiresAt = job.createdAt + Duration.ofHours(config.getDraftHours()).toMillis();
        job.state = State.DRAFT;
        job.stance = stance;
        job.revision = policy.revision();
        for (UploadSpec file : request.files()) job.inputs.add(input(file));
        if (job.inputs.size() > 1 && job.inputs.stream().anyMatch(Input::pdf)) throw bad("一次可以上传一组照片或一份 PDF");
        long bytes = job.inputs.stream().mapToLong(Input::size).sum();
        if (bytes > policy.maxBytes()) throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "整批附件大小超限");
        synchronized (store) {
            var jobs = store.jobs();
            checkQueue(jobs, owner, null);
            if (store.directories().size() >= config.getMaxJobs()) throw busy("合同任务暂满，请稍后再试");
            checkStorage(jobs, bytes);
            store.create(job);
            return detail(job);
        }
    }

    public Offset upload(long owner, String jobId, String assetId, long offset, byte[] chunk) throws IOException {
        if (offset < 0 || chunk.length < 1 || chunk.length > CHUNK_BYTES) throw bad("分片大小或偏移无效");
        synchronized (store) {
            Job job = require(owner, jobId);
            Input file = job.state == State.DRAFT ? findInput(job, assetId) : null;
            if (job.repair != null && job.repair.input().id().equals(assetId)) file = job.repair.input();
            if (file == null) throw problem(HttpStatus.CONFLICT, "该上传已结束，请刷新任务");
            long current = store.received(job, file);
            if (offset > current || offset > file.size() - chunk.length) throw problem(HttpStatus.CONFLICT, "上传进度已变化，请刷新任务");
            if (file.chunkHashes() != null && (offset % CHUNK_BYTES != 0 || chunk.length != Math.min(CHUNK_BYTES, file.size() - offset)
                    || !file.chunkHashes().get((int) (offset / CHUNK_BYTES)).equals(digest(chunk)))) {
                throw problem(HttpStatus.CONFLICT, "重新选择的文件与原上传文件不一致");
            }
            Path path = store.asset(job, file);
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                int overlap = (int) Math.min(current - offset, chunk.length);
                if (overlap > 0) checkOverlap(channel, offset, chunk, overlap);
                int remaining = chunk.length - overlap;
                if (remaining == 0) return new Offset(current);
                try {
                    channel.position(current);
                    var bytes = ByteBuffer.wrap(chunk, overlap, remaining);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    if (current + remaining == file.size()) verifyUpload(path, file);
                    channel.force(false);
                } catch (IOException | RuntimeException error) {
                    channel.truncate(current);
                    throw error;
                }
            }
            return new Offset(current + Math.max(0, offset + chunk.length - current));
        }
    }

    private void verifyUpload(Path path, Input input) throws IOException {
        if (input.pdf()) {
            try (var stream = Files.newInputStream(path)) {
                String header = new String(stream.readNBytes(1024), StandardCharsets.ISO_8859_1);
                if (!header.contains("%PDF-")) throw bad("文件内容不是 PDF");
            }
        } else {
            documents.verifyPhoto(path, input.format());
        }
    }

    public Detail start(long owner, String id, Consent consent) throws IOException {
        checkConsent(consent.revision());
        synchronized (store) {
            Job job = require(owner, id);
            if (PROCESSING.contains(job.state) && policy.revision().equals(job.revision)) return detail(job);
            if (job.state != State.DRAFT && job.state != State.PAUSED) throw problem(HttpStatus.CONFLICT, "合同任务已开始");
            for (Input input : job.inputs) {
                if (store.received(job, input) != input.size()) throw bad("请等文件上传完成再开始");
                if (job.state == State.DRAFT) verifyUpload(store.asset(job, input), input);
            }
            queue(job);
            return detail(job);
        }
    }

    public Detail prepareReplacement(long owner, String id, int page, Replacement replacement) throws IOException {
        checkConsent(replacement.revision());
        Input file = input(replacement.file());
        if (file.pdf()) throw bad("请选择一张合同照片");
        synchronized (store) {
            Job job = require(owner, id);
            requireRetry(job, page);
            if (job.inputs.get(0).pdf()) throw bad("PDF 页面可直接重试识别");
            var jobs = store.jobs();
            checkQueue(jobs, owner, id);
            if (job.inputs.stream().mapToLong(Input::size).sum() - job.inputs.get(page - 1).size() + file.size() > policy.maxBytes())
                throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "加入替换页后，整批附件大小超限");
            checkStorage(jobs.stream().filter(j -> !id.equals(j.id)).toList(),
                    job.inputs.stream().mapToLong(Input::size).sum() + file.size());
            job.repair = new Repair(page, file);
            store.save(job);
            return detail(job);
        }
    }

    public Detail retry(long owner, String id, int page, Consent consent) throws IOException {
        checkConsent(consent.revision());
        synchronized (store) {
            Job job = require(owner, id);
            if (page == 0) {
                if (!FINISHED.contains(job.state) || job.pages == 0) throw bad("当前任务无法重新汇总");
            } else {
                requireRetry(job, page);
            }
            checkQueue(store.jobs(), owner, id);
            if (page > 0 && job.repair != null && job.repair.page() == page) {
                Input replacement = job.repair.input();
                if (store.received(job, replacement) != replacement.size()) throw bad("请先上传完整的替换照片");
                verifyUpload(store.asset(job, replacement), replacement);
                Files.deleteIfExists(store.source(job, page));
                Files.deleteIfExists(store.review(job, page));
                job.inputs.set(page - 1, replacement);
                job.repair = null;
            } else if (page > 0) {
                ScanPage scanned = store.read(store.source(job, page), ScanPage.class);
                if (scanned == null || scanned.warning() != null || !scanned.scanned()) Files.deleteIfExists(store.source(job, page));
                Files.deleteIfExists(store.review(job, page));
            }
            Files.deleteIfExists(store.cross(job));
            queue(job);
            return detail(job);
        }
    }

    public void delete(long owner, String id) throws IOException {
        synchronized (store) {
            Job job = require(owner, id);
            job.state = State.DELETED;
            store.save(job);
            try { store.remove(store.jobPath(id)); }
            catch (IOException ignored) { /* The periodic cleanup retries physical deletion. */ }
        }
    }

    public Binary download(long owner, String id, String inputId) throws IOException {
        synchronized (store) {
            Job job = require(owner, id);
            Input input = findInput(job, inputId);
            if (input == null || store.received(job, input) != input.size()) throw notFound();
            return new Binary(store.asset(job, input), null, "application/octet-stream", input.name(), input.size());
        }
    }

    public Binary preview(long owner, String id, int page) throws IOException {
        Job job;
        synchronized (store) { job = require(owner, id); requirePage(job, page); }
        Input input = job.inputs.get(job.inputs.get(0).pdf() ? 0 : page - 1);
        if (!input.pdf()) return new Binary(store.asset(job, input), null, input.format().equals("jpg") ? "image/jpeg" : "image/png", null, input.size());
        if (!previews.tryAcquire()) throw busy("预览繁忙，请稍后重试");
        try (var pdf = documents.pdf(store.asset(job, input))) {
            byte[] bytes = pdf.raster(page, 1800);
            synchronized (store) { require(owner, id); }
            return new Binary(null, bytes, "image/jpeg", null, bytes.length);
        } finally { previews.release(); }
    }

    Job take() throws IOException {
        if (!policy.enabled()) return null;
        synchronized (store) {
            long now = System.currentTimeMillis();
            for (Job job : store.jobs().stream().filter(j -> visible(j, now) && PROCESSING.contains(j.state))
                    .sorted(Comparator.comparingLong(j -> j.createdAt)).toList()) {
                if (!policy.revision().equals(job.revision)) {
                    job.state = State.PAUSED;
                    job.issue = "处理服务已变更，请确认后继续";
                    store.save(job);
                    continue;
                }
                job.state = State.SCANNING;
                store.save(job);
                return job;
            }
            return null;
        }
    }

    boolean active(Job job) {
        synchronized (store) {
            try { return stillProcessing(job); }
            catch (IOException error) { return false; }
        }
    }

    private boolean stillProcessing(Job job) throws IOException {
        Job current = store.job(job.id);
        return !Thread.currentThread().isInterrupted() && visible(current, System.currentTimeMillis())
                && PROCESSING.contains(current.state) && policy.revision().equals(current.revision);
    }

    void update(Job job, State state, String issue) throws IOException {
        synchronized (store) {
            if (!stillProcessing(job)) return;
            job.state = state;
            job.issue = issue;
            if (FINISHED.contains(state)) job.expiresAt = System.currentTimeMillis() + Duration.ofHours(policy.retentionHours()).toMillis();
            store.save(job);
        }
    }

    <T> void checkpoint(Job job, Path path, T value) throws IOException {
        synchronized (store) { if (stillProcessing(job)) store.write(path, value); }
    }

    boolean useModel(Job job, boolean ocr) {
        synchronized (store) {
            try {
                if (!stillProcessing(job)) return false;
                String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
                Usage usage = store.read(store.usage(), Usage.class);
                if (usage == null || !today.equals(usage.date())) usage = new Usage(today, 0, 0);
                if (ocr ? usage.ocr() >= config.getDailyOcrPages() : usage.review() >= config.getDailyReviewCalls()) return false;
                store.write(store.usage(), ocr ? new Usage(today, usage.ocr() + 1, usage.review()) : new Usage(today, usage.ocr(), usage.review() + 1));
                return true;
            } catch (IOException error) { return false; }
        }
    }

    void cleanup() throws IOException {
        synchronized (store) {
            long now = System.currentTimeMillis();
            for (Path path : store.directories()) {
                try {
                    Job job = store.job(path.getFileName().toString());
                    if (visible(job, now)) store.removeUnreferencedAssets(job);
                    else store.remove(path);
                } catch (IOException ignored) { /* Busy files can be removed in the next pass. */ }
            }
        }
    }

    private Detail detail(Job job) throws IOException {
        var files = new ArrayList<FileView>();
        for (Input file : job.inputs) files.add(view(job, file));
        var pages = new ArrayList<PageView>();
        var risks = new java.util.LinkedHashSet<Risk>();
        for (int page = 1; page <= job.pages; page++) {
            ScanPage scanned = store.read(store.source(job, page), ScanPage.class);
            PageReview reviewed = store.read(store.review(job, page), PageReview.class);
            String warning = scanned == null ? null : scanned.warning();
            if (warning == null && scanned != null && reviewed == null && FINISHED.contains(job.state)) warning = "此页审查未完成，可重试";
            pages.add(new PageView(page, scanned != null && scanned.scanned(), reviewed != null, warning));
            if (reviewed != null) risks.addAll(reviewed.risks());
        }
        var cross = store.read(store.cross(job), CrossReview.class);
        if (cross != null) risks.addAll(cross.risks());
        return new Detail(summary(job), job.stance, job.issue, files, pages, List.copyOf(risks), cross == null ? null : cross.complete(),
                job.repair == null ? null : job.repair.page(), job.repair == null ? null : view(job, job.repair.input()));
    }

    private FileView view(Job job, Input input) throws IOException {
        return new FileView(input.id(), input.name(), input.format(), input.size(), store.received(job, input), input.optimized());
    }

    private Summary summary(Job job) {
        String title = job.inputs.size() == 1 ? job.inputs.get(0).name() : job.inputs.size() + " 张合同照片";
        return new Summary(job.id, title, job.state.name(), job.createdAt, job.expiresAt, job.pages);
    }

    private Job require(long owner, String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw notFound();
        Job job = store.job(id);
        if (!visible(job, System.currentTimeMillis()) || job.owner != owner) throw notFound();
        return job;
    }

    private Input input(UploadSpec file) {
        if (file == null || file.format() == null || !Set.of("jpg", "png", "pdf").contains(file.format())) throw bad("格式须为照片或 PDF");
        long limit = file.format().equals("pdf") ? policy.maxBytes() : Math.min(policy.maxPhotoBytes(), policy.maxBytes());
        if (file.size() < 1 || file.size() > limit) throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "文件为空或超出大小上限");
        String name = Objects.toString(file.name(), "").replaceAll("[\\p{Cntrl}\\p{Cf}\\\\/]", "_").strip();
        if (name.isBlank() || name.length() > 180) throw bad("文件名为空或过长，请重命名后上传");
        List<String> hashes = file.chunkHashes();
        if (hashes != null && (hashes.size() != (file.size() + CHUNK_BYTES - 1) / CHUNK_BYTES
                || hashes.stream().anyMatch(hash -> hash == null || !hash.matches("[a-f0-9]{64}")))) {
            throw bad("文件校验信息无效，请重新选择文件");
        }
        return new Input(store.id(), name, file.format(), file.size(), file.optimized() && !file.format().equals("pdf"), hashes);
    }

    private void checkQueue(List<Job> jobs, long owner, String except) {
        long now = System.currentTimeMillis();
        var active = jobs.stream().filter(j -> visible(j, now) && !j.id.equals(except) && (BUSY.contains(j.state) || j.repair != null)).toList();
        if (active.stream().anyMatch(j -> j.owner == owner)) throw problem(HttpStatus.CONFLICT, "已有待处理任务，可在审查记录中继续");
        if (active.size() >= config.getMaxQueued()) throw busy("合同任务队列已满，请稍后重试");
    }

    private void checkStorage(List<Job> jobs, long requested) throws IOException {
        long reserved = requested + DISK_MARGIN;
        for (Job job : jobs) {
            reserved += job.inputs.stream().mapToLong(Input::size).sum();
            if (job.repair != null) reserved += job.repair.input().size();
        }
        if (reserved > config.getStorageBytes() || requested + DISK_MARGIN > store.usableSpace())
            throw busy("合同扫描存储空间暂不足，请稍后重试");
    }

    private void checkConsent(String revision) {
        if (!policy.enabled()) throw busy("合同扫描暂不可用");
        if (!policy.revision().equals(revision)) throw problem(HttpStatus.CONFLICT, "处理服务已变更，请刷新后确认");
    }

    private void requireRetry(Job job, int page) {
        if (!FINISHED.contains(job.state)) throw problem(HttpStatus.CONFLICT, "请等待当前任务完成");
        requirePage(job, page);
    }

    private void requirePage(Job job, int page) {
        if (page < 1 || page > job.pages) throw notFound();
    }

    private void queue(Job job) throws IOException {
        job.state = State.QUEUED;
        job.revision = policy.revision();
        job.issue = "";
        job.expiresAt = System.currentTimeMillis() + Duration.ofHours(policy.retentionHours()).toMillis();
        store.save(job);
    }

    private static void checkOverlap(FileChannel channel, long offset, byte[] chunk, int limit) throws IOException {
        ByteBuffer existing = ByteBuffer.allocate(limit);
        channel.position(offset);
        while (existing.hasRemaining() && channel.read(existing) >= 0) { /* Read the complete range. */ }
        byte[] persisted = existing.array();
        if (existing.hasRemaining() || !java.util.Arrays.equals(persisted, 0, limit, chunk, 0, limit))
            throw problem(HttpStatus.CONFLICT, "重新选择的文件与已上传部分不一致");
    }

    private static Input findInput(Job job, String fileId) {
        return job.inputs.stream().filter(f -> f.id().equals(fileId)).findFirst().orElse(null);
    }

    private static boolean visible(Job job, long now) { return job != null && job.state != State.DELETED && job.expiresAt > now; }
    private static String label(String configured, String provider) { return configured == null || configured.isBlank() ? provider : configured; }
    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Problem notFound() { return problem(HttpStatus.NOT_FOUND, "合同任务或文件不存在，可能已过期"); }
    private static Problem bad(String message) { return problem(HttpStatus.BAD_REQUEST, message); }
    private static Problem busy(String message) { return problem(HttpStatus.SERVICE_UNAVAILABLE, message); }
    private static Problem problem(HttpStatus status, String message) { return new Problem(status, message); }
}
