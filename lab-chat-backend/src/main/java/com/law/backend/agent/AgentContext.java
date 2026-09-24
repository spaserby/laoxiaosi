package com.law.backend.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 协作链共享上下文：链上各角色的输入/输出载体
 * <p>
 * 字段即协作链的状态：question/history 是输入（只读），plan 是 Planner 的产出（可写）。
 * 未来 Retriever 接通后可再加 retrievedRefs 字段、Reviewer 加 reviewResult 字段——
 * 上下文随链的演进渐进生长，不提前塞用不上的字段。
 */
@Getter
public class AgentContext {

    /** 会话 ID */
    private final String sessionId;

    /** 用户原始问题 */
    private final String question;

    /** 会话历史（Spring AI Message） */
    private final List<Message> history;

    /** Planner 产出：子任务计划（骨架期恒为单子任务） */
    @Setter
    private Plan plan;

    public AgentContext(String sessionId, String question, List<Message> history) {
        this.sessionId = sessionId;
        this.question = question;
        this.history = history;
    }
}
