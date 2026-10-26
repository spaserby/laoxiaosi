package com.law.backend.memory;

public record SummaryState(String sessionId, String content, long coveredUntilMessageId, long version,
                           boolean legacy) {}
