package com.law.backend.tool;

import com.law.backend.rag.CitationRegistry;
import com.law.backend.rag.KnowledgeRetrievalService;
import com.law.backend.rag.LawCitation;
import com.law.backend.rag.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 法律条文检索工具
 * <p>
 * <b>Naive RAG 的问题</b>：每轮对话无条件前置检索 Top-3 拼进 System Prompt——
 * 闲聊也白花 embedding 调用；检索词就是用户原话，召回质量差；只能检一轮。
 * <p>
 * <b>工具化后（Agentic RAG）</b>：模型自主决定——要不要检索（闲聊不调，省 token）、
 * 用什么检索词（自己改写成法律术语）、检索几次（多问题可多次调用）。
 * 这一步落地后，架构从"Workflow（流程写死）"跨入"Agent（模型自主决策步骤）"。
 * <p>
 * <b>迭代上限（方案②配套）</b>：ReAct 循环若无停止条件，模型可能无限"再查一次"烧 token。
 * ChatService 每次对话通过 {@link ToolContext} 注入检索预算（多工具共享的
 * {@link ToolBudget}，与联网搜索 webSearch 同池扣减），每次调用扣减，超限返回提示文本
 * 让模型基于已有信息作答——预算值按路线差异化配置。
 * <p>
 * <b>引用可信度</b>：每次命中登记进请求级 {@link CitationRegistry}
 * （经 ToolContext 注入），供 AnswerReviewer 校验答案引用的真伪——
 * 工具多跳检索的命中与强制打底检索的命中汇入同一个登记器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchLawTool {

    private final KnowledgeRetrievalService retrievalService;
    private final RagProperties ragProperties;

    /**
     * 检索法律条文：模型回答法律问题前调用，返回最相关的条文原文（含出处）
     */
    @Tool(description = "检索与法律问题相关的法律条文。当用户咨询劳动合同、经济补偿、加班费、"
            + "试用期、诉讼时效、诉讼费、合同违约、赔偿等法律问题时，回答前必须先调用本工具获取条文依据，"
            + "并在回答中注明法律名称与条文编号。闲聊、问候、通用知识问题不需要调用。")
    public String searchLaw(
            @ToolParam(description = "检索关键词。不要照抄用户原话，请提炼为法律术语，"
                    + "例如用户问'被公司辞退了能拿多少钱'应检索'解除劳动合同 经济补偿 标准'") String query,
            @ToolParam(description = "可选分类过滤，如 劳动 / 民事 / 刑事；不需要限制时不传", required = false) String category,
            ToolContext toolContext) {
        // RAG 总开关关闭时，明确告知模型走通用知识
        if (!ragProperties.isEnabled()) {
            return "法律知识库未启用，请基于通用知识回答并提醒用户答案仅供参考。";
        }
        // 检索预算：由 ChatService 每次对话注入（路线差异化，与 webSearch 共享同一池），超限即"断供"
        ToolBudget budget = (ToolBudget) toolContext.getContext().get(ToolBudget.KEY);
        if (budget != null && !budget.tryAcquire()) {
            log.info("检索预算已耗尽: query={}, 已用={}/{}", query, budget.usedCount(), budget.maxCalls());
            // 连续拒绝 ≥3 次升级强制收敛指令，终结检索成瘾空转
            return budget.denialCount() >= ToolBudget.HARD_DENY_AFTER
                    ? "检索次数已达本轮上限且连续多次调用被拒。【强制】立即停止一切工具调用，"
                            + "基于已检索到的条文与已有依据直接生成完整回答。"
                    : "检索次数已达本轮上限（" + budget.maxCalls() + " 次），不要再调用 searchLaw 或 webSearch，"
                            + "请基于已检索到的条文直接作答。";
        }
        List<LawCitation> cites = retrievalService.retrieveCitations(query, category);
        // 命中登记进请求级引用登记器（审校真伪校验的数据源之一）
        CitationRegistry registry = (CitationRegistry) toolContext.getContext().get(CitationRegistry.KEY);
        if (registry != null) {
            registry.register(cites);
        }
        if (cites.isEmpty()) {
            // 检索降级（向量库不可用）或无结果/未过阈值：提示模型换关键词重试或基于通用知识回答
            return "未检索到相关条文。可尝试更换更简洁的法律术语重试一次；若仍无结果，请基于通用知识回答并说明未找到直接依据。";
        }
        log.info("searchLaw 工具调用: query={}, 命中={} 条", query, cites.size());
        return cites.stream().map(LawCitation::text).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    /**
     * 早期 Budget 内部类已泛化为独立的 {@link ToolBudget}（多工具共享预算），
     * 预算注入入口见 {@link ToolBudget#context(int)}。
     */
}
