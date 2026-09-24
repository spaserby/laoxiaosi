package com.law.backend.model;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 关键词硬规则层
 * <p>
 * 定位：确定性旁路。用户显式指令、合规黑名单这类"不该让 AI 决定"的场景走硬规则，
 * 0 成本 0 延迟；未命中则放行给语义层。
 */
@RequiredArgsConstructor
public class KeywordRouteLayer implements RouteLayer {

    private final ModelRouteProperties properties;

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public Optional<String> tryDecide(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<String, List<String>> entry : properties.getKeywords().entrySet()) {
            for (String keyword : entry.getValue()) {
                if (userMessage.contains(keyword)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }
}
