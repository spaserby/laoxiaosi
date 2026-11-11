package com.law.backend.memory;
import java.time.LocalDateTime;

/** 数据库原文，不混入任何内部摘要、工具消息或模型提示词。 */
public record StoredMessage(long id, String role, String content, LocalDateTime createdAt,
                            String turnId, String status, String evidence) {}
