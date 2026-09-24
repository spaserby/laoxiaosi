package com.law.backend.memory;

import com.law.backend.model.ModelRegistry;
import com.law.backend.model.ModelRouteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PostgreSQL 的 ChatMemory 实现
 * <p>
 * <b>迁移动机</b>：记忆是持久资产而非缓存——Redis 24h TTL 造成"历史会话点进去空"
 * 的悬空体验；落 PG 后历史永久可回看，上下文窗口仍由
 * verbatimRounds + 滚动摘要约束有界。Redis 退居缓存/锁/配额等易失态。
 * <p>
 * <b>存储</b>：chat_message（id 自增即时序）+ chat_summary（早期历史滚动摘要）；
 * 窗口裁剪 = 删最旧 N 行 + 被裁内容异步摘要（与 Redis 版语义一致）。
 * <p>
 * <b>多实例安全</b>：状态全在 PG，任意实例读写同一份记忆（Redis RList 时代也共享，
 * 但 TTL 语义各实例不可控）。
 */
@Slf4j
@Component
@EnableConfigurationProperties(ChatMemoryProperties.class)
@RequiredArgsConstructor
public class PgChatMemory implements ChatMemory {

    /** 摘要提示词（限字 + 保留关键事实），与 Redis 版同源 */
    private static final String SUMMARY_PROMPT =
            "把以下对话历史总结为 100 字以内摘要，保留关键事实（咨询过什么主题、给出过什么结论、引用过哪些法条或来源），直接输出摘要正文：";

    private final MessageMapper messageMapper;
    private final SummaryMapper summaryMapper;
    private final ChatMemoryProperties properties;
    private final ModelRegistry modelRegistry;
    private final ModelRouteProperties routeProperties;

    /**
     * 追加消息 → 窗口裁剪（超 verbatimRounds 轮删最旧）→ 被裁部分异步滚动摘要
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            messageMapper.insert(conversationId, message.getMessageType().name(), message.getText());
        }
        // 窗口裁剪：原文只保留 verbatimRounds 轮（一轮 = 用户+助手两条）
        int maxRecords = properties.getVerbatimRounds() * 2;
        long count = messageMapper.countBySession(conversationId);
        if (count > maxRecords) {
            int overflow = (int) (count - maxRecords);
            List<MessageRecord> discarded = messageMapper.selectOldest(conversationId, overflow);
            messageMapper.deleteOldest(conversationId, overflow);
            if (properties.isSummaryEnabled() && !discarded.isEmpty()) {
                summarizeAsync(conversationId, discarded);
            }
        }
    }

    /**
     * 读取会话历史：早期摘要 SystemMessage 置顶 + 窗口内原文（按对话顺序）
     */
    @Override
    public List<Message> get(String conversationId) {
        List<Message> result = new ArrayList<>();
        try {
            String summary = summaryMapper.select(conversationId);
            if (summary != null && !summary.isBlank()) {
                result.add(new SystemMessage("【 Earlier Conversation Summary 】" + summary));
            }
        } catch (Exception e) {
            log.warn("读取会话摘要失败，降级为仅近期原文: sessionId={}, error={}", conversationId, e.getMessage());
        }
        for (MessageRecord record : messageMapper.selectBySession(conversationId)) {
            result.add(toMessage(record));
        }
        return result;
    }

    /**
     * 清除会话全部历史（消息 + 摘要）
     */
    @Override
    public void clear(String conversationId) {
        messageMapper.deleteBySession(conversationId);
        summaryMapper.delete(conversationId);
    }

    /**
     * 异步滚动摘要（boundedElastic 不阻塞主流程）；
     * 新摘要 = f(旧摘要 + 本批裁掉消息)；失败降级为直接丢弃
     */
    private void summarizeAsync(String sessionId, List<MessageRecord> discarded) {
        reactor.core.publisher.Mono.fromRunnable(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                String oldSummary = summaryMapper.select(sessionId);
                if (oldSummary != null && !oldSummary.isBlank()) {
                    sb.append("【已有摘要】").append(oldSummary).append('\n');
                }
                for (MessageRecord record : discarded) {
                    sb.append(record.getType()).append(':').append(record.getContent()).append('\n');
                }
                String[] target = routeProperties.resolve(properties.getSummaryRoute());
                String summary = modelRegistry.getRawClient(target[0], target[1]).prompt()
                        .system(SUMMARY_PROMPT)
                        .user(sb.toString())
                        .call()
                        .content();
                if (summary != null && !summary.isBlank()) {
                    summaryMapper.upsert(sessionId, summary);
                    log.info("会话摘要更新: sessionId={}, 裁掉={} 条", sessionId, discarded.size());
                }
            } catch (Exception e) {
                log.warn("会话摘要失败，降级为丢弃早期消息: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
    }

    /**
     * MessageRecord 转 Spring AI Message（MessageType 枚举与内置子类一一对应）
     */
    private Message toMessage(MessageRecord record) {
        MessageType type = MessageType.valueOf(record.getType());
        return switch (type) {
            case USER -> new UserMessage(record.getContent());
            case ASSISTANT -> new AssistantMessage(record.getContent());
            case SYSTEM -> new SystemMessage(record.getContent());
            case TOOL -> new UserMessage(record.getContent()); // 工具消息按用户消息处理
        };
    }
}
