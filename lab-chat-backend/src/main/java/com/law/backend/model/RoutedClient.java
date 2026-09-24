package com.law.backend.model;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * 路由结果：路线 + 模型客户端 + 该路线的工具集与检索预算
 * <p>
 * route() 早期仅返回 ChatClient（模型选择）；现由路由结果同时携带
 * "工具预算"——不同路线挂不同工具集、不同迭代上限，路由从"选模型"升级为"选模型 + 配预算"。
 */
public record RoutedClient(
        /** 命中的路线名（default/reasoning/cheap/legal/fallback） */
        String route,
        /** 绑定了具体模型的对话客户端（已全局挂载 CommonTools） */
        ChatClient client,
        /** 该路线按需挂载的工具实例列表（可能为空 = 无按需工具） */
        List<Object> tools,
        /** 本轮 searchLaw 检索调用上限（ReAct 循环停止条件） */
        int maxToolCalls) {
}
