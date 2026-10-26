package com.law.backend.memory;

import com.law.backend.attachment.AttachmentTokens;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 数据库游标即持久任务源；单个有界扫描，不在请求线程或数据库事务中调用模型。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConversationSummaryWorker {
    private final MessageMapper messages;
    private final SummaryMapper summaries;
    private final ChatMemoryProperties properties;
    private final RollingSummaryModel model;
    private final AttachmentTokens tokens;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "conversation-summary"); thread.setDaemon(true); return thread;
    });

    @Scheduled(fixedDelayString = "${legal.ai.chat-memory.summary-poll-ms:15000}", initialDelayString = "${legal.ai.chat-memory.summary-poll-ms:15000}")
    public void schedule() {
        if (!scanning.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { scan(); }
                catch (Exception e) { log.warn("记忆后台扫描失败: type={}", e.getClass().getSimpleName()); }
                finally { scanning.set(false); }
            });
        } catch (RejectedExecutionException e) { scanning.set(false); }
    }

    public void scan() {
        // 即使关闭摘要，也恢复异常终止的 RUNNING 轮次；原文不受影响。
        messages.interruptStale(LocalDateTime.now().minusMinutes(Math.max(10, properties.getAbandonedTurnMinutes())));
        if (!properties.isSummaryEnabled()) return;
        for (String session : summaries.pending(LocalDateTime.now(), properties.rounds(), 10)) process(session);
    }

    public void process(String sessionId) {
        String lease = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (summaries.claim(sessionId, lease, now, now.plusMinutes(10)) == 0) return;
        try {
            SummaryState state = summaries.state(sessionId);
            if (state == null) return;
            Long boundary = messages.summaryBoundary(sessionId, properties.rounds() - 1);
            if (boundary == null) return;
            var starts = messages.summaryTurns(sessionId, state.coveredUntilMessageId(), boundary,
                    Math.max(1, Math.min(16, properties.getSummaryBatchTurns())));
            String summary = state.content();
            StringBuilder evidence = new StringBuilder();
            long covered = state.coveredUntilMessageId();
            // 只读固定批次的终态轮次。过长的单轮分多次模型输入，全部成功才提交该批游标。
            for (long start : starts) {
                for (StoredMessage message : messages.turn(sessionId, start)) {
                    String text = "\n[消息ID=" + message.id() + ",角色=" + message.role() + ",状态=" + message.status() + "]\n" + message.content();
                    while (!text.isEmpty()) {
                        int available = properties.getSummaryInputTokens() - tokens.chat(RollingSummaryModel.PROMPT)
                                - tokens.chat(RollingSummaryModel.input(summary, evidence.toString())) - 128;
                        if (available < 128) {
                            if (evidence.isEmpty()) throw new IllegalStateException("摘要预算不足");
                            summary = generate(sessionId, lease, summary, evidence.toString()); evidence.setLength(0); continue;
                        }
                        int end = tokens.prefix(text, available, false);
                        if (end == 0) throw new IllegalStateException("摘要片段超出预算");
                        evidence.append(text, 0, end);
                        text = text.substring(end);
                        if (!text.isEmpty()) {
                            summary = generate(sessionId, lease, summary, evidence.toString()); evidence.setLength(0);
                            text = "\n[续接消息ID=" + message.id() + "]\n" + text;
                        }
                    }
                    covered = message.id();
                }
            }
            if (evidence.isEmpty() || covered == state.coveredUntilMessageId()) {
                summaries.retry(sessionId, lease, LocalDateTime.now().plusSeconds(15)); return;
            }
            summary = generate(sessionId, lease, summary, evidence.toString());
            summaries.commit(sessionId, lease, state.version(), state.coveredUntilMessageId(), covered, summary, LocalDateTime.now());
        } catch (Exception e) {
            Integer failures = summaries.failures(sessionId, lease);
            if (failures != null) summaries.retry(sessionId, lease,
                    LocalDateTime.now().plusSeconds(Math.min(3600, 15L << Math.min(8, failures))));
            // 不记录异常正文、原文或模型响应，避免将咨询隐私写入日志。
            log.warn("历史摘要失败，原文及游标保留待重试: type={}", e.getClass().getSimpleName());
        }
    }

    private String generate(String sessionId, String lease, String summary, String evidence) {
        LocalDateTime now = LocalDateTime.now();
        if (summaries.renew(sessionId, lease, now, now.plusMinutes(10)) == 0)
            throw new IllegalStateException("摘要任务已失效");
        if (tokens.chat(RollingSummaryModel.PROMPT) + tokens.chat(RollingSummaryModel.input(summary, evidence)) + 64
                > properties.getSummaryInputTokens()) throw new IllegalStateException("摘要输入超出预算");
        String result = model.summarize(summary, evidence);
        if (result == null || result.isBlank() || tokens.chat(result) > properties.getSummaryMaxTokens())
            throw new IllegalStateException("摘要输出无效或超出预算");
        return result;
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
