package com.law.backend.tool;

import com.law.backend.rag.CitationRegistry;
import com.law.backend.rag.LawCitation;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

/**
 * 计算器类工具的确定性依据登记
 * <p>
 * <b>解决的问题</b>：CitationRegistry 原先只登记 searchLaw 的检索命中，计算器回答里
 * 携带的法条依据（如"依据《劳动合同法》第47条"）不在 AnswerReviewer 的校验覆盖内。
 * 计算器把自身依据以 score=1.0（确定性来源，非检索相似度）登记进请求级登记器，
 * 审校 gate 对"计算型回答"同样能核验引用真伪。
 * <p>
 * 不影响前端引用卡：citation 事件仍只推送检索命中（groundedCitations）。
 */
public final class ToolBasis {

    private ToolBasis() {
    }

    /**
     * 登记一条计算依据（lawName|articleNo 键入登记器）
     */
    public static void register(ToolContext ctx, String lawName, String articleNo) {
        if (ctx == null) {
            return;
        }
        Object o = ctx.getContext().get(CitationRegistry.KEY);
        if (o instanceof CitationRegistry registry) {
            registry.register(List.of(new LawCitation(null, lawName, articleNo, "", 1.0, lawName + articleNo, false)));
        }
    }
}
