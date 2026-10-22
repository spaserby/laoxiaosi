package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** 全文模式遍历全部父块；覆盖率来自程序计数，不能由 Top-K 或模型宣称。 */
@Component
public class AttachmentReview {
    private final AttachmentRepository repository;
    private final AttachmentReviewModel model;
    private final AttachmentTokens tokens;
    private final FileProperties properties;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(1);
    public AttachmentReview(AttachmentRepository repository, AttachmentReviewModel model, AttachmentTokens tokens, FileProperties properties) {
        this.repository = repository; this.model = model; this.tokens = tokens; this.properties = properties;
    }
    public AttachmentContext review(AttachmentRepository.Scope scope, List<AttachmentRepository.FileRecord> files, String question,
                                    int budget, Consumer<String> stage, BooleanSupplier cancelled) {
        if (!slots.tryAcquire()) throw new IllegalArgumentException("已有全文审查正在执行，请稍后重试");
        try { return run(scope, files, question, budget, stage, cancelled); }
        finally { slots.release(); }
    }
    private AttachmentContext run(AttachmentRepository.Scope scope, List<AttachmentRepository.FileRecord> files, String question,
                                  int budget, Consumer<String> stage, BooleanSupplier cancelled) {
        var parents = repository.parents(scope, files.stream().map(AttachmentRepository.FileRecord::fileId).toList());
        if (parents.isEmpty()) throw new IllegalArgumentException("未提取出可审查正文，请检查附件解析与 OCR 状态后重试");
        if (parents.size() > properties.getMaxReviewSections()) throw new IllegalArgumentException("全文审查超过 " + properties.getMaxReviewSections() + " 个章节，请拆分文档；尚未开始审查");
        Map<String, String> names = new LinkedHashMap<>();
        files.forEach(f -> names.put(f.fileId(), f.name()));
        List<String> warnings = new ArrayList<>(files.stream().flatMap(f -> f.warnings().stream()).distinct().toList());
        List<String> notes = new ArrayList<>();
        List<AttachmentContext.Source> sources = new ArrayList<>();
        int checked = 0;
        for (var parent : parents) {
            check(scope, files, cancelled);
            String label = "附件" + (new ArrayList<>(names.keySet()).indexOf(parent.fileId()) + 1) + "·P" + (parent.parentId() + 1);
            stage.accept("全文审查：" + (sources.size() + 1) + "/" + parents.size() + " 章节…");
            sources.add(new AttachmentContext.Source(parent.fileId(), names.get(parent.fileId()), parent.parentId(), parent.source(), parent.start(), parent.end(), label));
            try {
                String material = "[" + label + "]《" + names.get(parent.fileId()) + "》 " + parent.source() + "\n" + parent.text();
                verifyInput(question, material);
                String note = model.inspect(question, material, false);
                if (tokens.chat(note) > properties.getReviewOutputTokens() * 2) throw new IllegalStateException();
                notes.add("[" + label + "]\n" + note);
                checked++;
            } catch (IllegalArgumentException e) { throw e; }
            catch (Exception e) { warnings.add("[" + label + "] 审查未完成，需重试"); }
        }
        // 分层合并始终遍历全部记录，不把后半部记录 substring 掉。
        for (int round = 0; tokens.chat(String.join("\n", notes)) > budget && round < 4; round++) {
            List<String> reduced = new ArrayList<>();
            List<String> batch = new ArrayList<>();
            int inputBudget = Math.min(6000, properties.getContextWindowTokens() - properties.getOutputReserveTokens() - properties.getSafetyReserveTokens() - tokens.chat(question) - 512);
            for (String note : notes) {
                if (!batch.isEmpty() && tokens.chat(String.join("\n", batch) + "\n" + note) > inputBudget) {
                    check(scope, files, cancelled); stage.accept("汇总全部章节的审查记录…");
                    String joined = String.join("\n", batch); verifyInput(question, joined);
                    reduced.add(model.inspect(question, joined, true)); batch.clear();
                }
                if (tokens.chat(note) > inputBudget) throw new IllegalArgumentException("审查记录过长，请拆分文档重试");
                batch.add(note);
            }
            if (!batch.isEmpty()) {
                check(scope, files, cancelled);
                String joined = String.join("\n", batch); verifyInput(question, joined);
                reduced.add(model.inspect(question, joined, true));
            }
            notes = reduced;
        }
        String result = String.join("\n", notes);
        if (tokens.chat(result) > budget) throw new IllegalArgumentException("全部审查记录无法纳入回答预算，请拆分文档重试；未截取审查记录");
        boolean complete = checked == parents.size() && files.stream().allMatch(f -> "READY".equals(f.status()));
        warnings.add("逐章审查已完成 " + checked + "/" + parents.size() + "；" + (complete ? "覆盖全部已解析正文，OCR 内容仍须核对原件" : "覆盖不完整，不能视为全文审查结论"));
        return new AttachmentContext(result, sources, warnings, checked, parents.size(), complete);
    }
    private void verifyInput(String question, String material) {
        if (tokens.chat(question) + tokens.chat(material) + 512 + properties.getOutputReserveTokens() + properties.getSafetyReserveTokens() > properties.getContextWindowTokens())
            throw new IllegalArgumentException("章节审查输入超过模型预算");
    }
    private void check(AttachmentRepository.Scope scope, List<AttachmentRepository.FileRecord> files, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("全文审查已停止");
        // 每次调用前再次校验期限和权限，防止长时间任务越过 TTL 或会话删除后继续读取。
        var current = new AttachmentRepository.Scope(scope.sessionId(), scope.ownerKey(), System.currentTimeMillis());
        files.forEach(f -> repository.require(current, f.fileId()));
    }
}
