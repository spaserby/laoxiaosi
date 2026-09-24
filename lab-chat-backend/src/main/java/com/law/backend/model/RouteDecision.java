package com.law.backend.model;

/**
 * LLM 路由层的结构化决策结果（方案 C：LLM 直接输出 JSON，entity() 反序列化）
 *
 * @param route  路线名（default/reasoning/cheap/legal 之一）
 * @param reason 选择理由（用于日志观察路由质量）
 */
public record RouteDecision(String route, String reason) {
}
