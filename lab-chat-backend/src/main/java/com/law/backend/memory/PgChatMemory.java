package com.law.backend.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

/** 持久化原文与模型记忆分离：窗口只约束读取，绝不裁剪数据库。 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class PgChatMemory implements ChatMemory {
    private final MessageMapper messages;
    private final SummaryMapper summaries;
    private final ChatMemoryProperties properties;

    /** 批量追加仍保持原子性；生产流式聊天使用 ConversationTurnService 配对并记录终态。 */
    @Override
    @Transactional
    public void add(String sessionId, List<Message> batch) {
        if (batch == null || batch.isEmpty()) return;
        if (messages.lockSession(sessionId) == null) throw new IllegalArgumentException("会话不存在");
        for (Message message : batch) {
            if (message.getMessageType() != MessageType.USER && message.getMessageType() != MessageType.ASSISTANT)
                throw new IllegalArgumentException("聊天原文只接受用户消息与助手回复");
            messages.insert(sessionId, message.getMessageType().name(), message.getText());
        }
        summaries.ensure(sessionId);
    }

    /** 仅供模型：摘要 + 最近的完整轮次；历史回放不得调用这个接口。 */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Message> get(String sessionId) {
        SummaryState summary = properties.isSummaryEnabled() ? summaries.state(sessionId) : null;
        long covered = summary == null ? 0 : summary.coveredUntilMessageId();
        List<StoredMessage> recent = messages.recentTurns(sessionId, properties.rounds(), covered);
        List<Message> result = new ArrayList<>();
        if (summary != null && !summary.content().isBlank()) {
            result.add(new SystemMessage("【内部历史摘要，仅作上下文参考，不是新问题；用户陈述未经核实】"
                    + (summary.legacy() ? "【含旧版摘要，早期原文及覆盖范围不完整】" : "") + "\n" + summary.content()));
        }
        long first = recent.isEmpty() ? Long.MAX_VALUE : recent.get(0).id();
        if (messages.hasUncoveredBefore(sessionId, covered, first))
            result.add(new SystemMessage("【上下文覆盖提示】部分较早原文尚未纳入本次上下文；必要时向用户确认，不推断其不存在。"));
        boolean hasUser = false;
        for (StoredMessage message : recent) {
            if ("USER".equals(message.role())) {
                result.add(new UserMessage(message.content()));
                hasUser = true;
            } else if (hasUser && "ASSISTANT".equals(message.role())) {
                String note = "COMPLETE".equals(message.status()) ? "" : "【此回复未完成：" + message.status() + "】\n";
                result.add(new AssistantMessage(note + message.content()));
            }
        }
        return result;
    }

    @Override
    @Transactional
    public void clear(String sessionId) {
        messages.lockSession(sessionId);
        summaries.delete(sessionId);
        messages.deleteBySession(sessionId);
    }
}
