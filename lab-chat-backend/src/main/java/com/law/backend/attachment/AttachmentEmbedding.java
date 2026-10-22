package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.*;

/** 远程 embedding 有截止时间和并发上限，供应商卡住也不能无限积累工作线程。 */
@Component
public class AttachmentEmbedding {
    private final EmbeddingModel model;
    private final FileProperties properties;
    private final ThreadPoolExecutor calls = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "attachment-embedding"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    public AttachmentEmbedding(EmbeddingModel model, FileProperties properties) { this.model = model; this.properties = properties; }
    public float[] query(String question) { return call(() -> model.embed(question)); }
    public List<float[]> batch(List<String> texts) { return call(() -> model.embed(texts)); }
    private <T> T call(Callable<T> operation) {
        Future<T> future;
        try { future = calls.submit(operation); }
        catch (RejectedExecutionException e) { throw new IllegalStateException("附件向量服务繁忙"); }
        try { return future.get(properties.getEmbeddingTimeoutSeconds(), TimeUnit.SECONDS); }
        catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("附件向量请求已停止"); }
        catch (Exception e) { future.cancel(true); throw new IllegalStateException("附件向量请求失败或超时"); }
    }
    @PreDestroy public void close() { calls.shutdownNow(); }
}
