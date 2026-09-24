package com.law.backend.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatMemory 配置属性
 * <p>
 * 配置前缀 legal.ai.chat-memory，直接写在本地 application.yml（不引入配置中心）。
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.chat-memory:
 *   window-size: 10                     # 保留最近 10 轮对话
 *   redis-key-prefix: "chat:memory:"    # Redis Key 前缀
 *   ttl-hours: 24                       # 会话过期时间（小时）
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.chat-memory")
public class ChatMemoryProperties {

    /** 保留最近几轮对话（一轮 = 用户消息 + 助手回复），默认 10。
     * @deprecated 已由 {@link #verbatimRounds} + 早期摘要取代（上下文压缩） */
    @Deprecated
    private int windowSize = 10;

    /** 原文保留轮数（超出部分裁剪并异步摘要），默认 4 轮 */
    private int verbatimRounds = 4;

    /** 早期历史摘要开关（关闭则裁剪部分直接丢弃，退化为旧窗口行为） */
    private boolean summaryEnabled = true;

    /** 摘要用的廉价路线名（route-tools 同一套路线体系） */
    private String summaryRoute = "cheap";

    // redis-key-prefix / ttl-hours 随 Redis 记忆实现一同下线（记忆落 PG 持久化）
}
