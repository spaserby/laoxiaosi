package com.law.backend.model;

import java.util.Optional;

/**
 * 路由决策层接口
 * <p>
 * 每一层独立决策：有把握就返回路线名，没把握返回空（交给下一层）。
 * ChainModelRouter 按配置顺序串联 keyword → semantic → llm 三层。
 */
public interface RouteLayer {

    /**
     * 层名，与配置 legal.ai.model.router.layers 中的值对应（keyword/semantic/llm）
     */
    String name();

    /**
     * 尝试决策路线
     *
     * @param userMessage 用户消息
     * @return 决策出的路线名；无法决策时返回 Optional.empty() 交给下一层
     */
    Optional<String> tryDecide(String userMessage);
}
