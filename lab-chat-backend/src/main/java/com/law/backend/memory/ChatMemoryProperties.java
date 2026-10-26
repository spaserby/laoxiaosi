package com.law.backend.memory;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "legal.ai.chat-memory")
public class ChatMemoryProperties {
    @Deprecated private int windowSize = 10;
    /** 只约束模型原文上下文，不控制数据库保留期限。 */
    private int verbatimRounds = 4;
    private boolean summaryEnabled = true;
    private String summaryRoute = "cheap";
    private int summaryBatchTurns = 8;
    private int summaryInputTokens = 12000;
    private int summaryMaxTokens = 1500;
    /** 应大于生成接口的最大运行时长。 */
    private int abandonedTurnMinutes = 60;
    public int rounds() { return Math.max(1, Math.min(20, verbatimRounds)); }
}
