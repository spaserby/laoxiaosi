package com.law.backend.model;

/**
 * 模型路由器门面接口
 * <p>
 * 对 ChatService 暴露的两个入口：
 * <ul>
 *   <li>{@link #route(String)} —— 本轮对话选模型 + 工具集 + 检索预算（由 ChainModelRouter 级联决策）</li>
 *   <li>{@link #fallback()} —— 主路线失败时的兜底路线（同样带工具预算）</li>
 * </ul>
 * <p>
 * 内部决策链见 {@link ChainModelRouter}：关键词硬规则 → 语义向量快筛 → LLM 分类兜底。
 */
public interface ModelRouter {

    /**
     * 按用户消息决策本轮使用的模型客户端 + 工具预算
     *
     * @param userMessage 用户消息
     * @return 路由结果（路线、客户端、按需工具、检索上限）
     */
    RoutedClient route(String userMessage);

    /**
     * 获取 fallback 路线的路由结果（主路线模型异常时兜底重试）
     */
    RoutedClient fallback();
}
