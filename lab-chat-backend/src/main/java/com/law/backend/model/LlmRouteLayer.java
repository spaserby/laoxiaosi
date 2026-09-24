package com.law.backend.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.Optional;

/**
 * LLM 路由层
 * <p>
 * 原理：用一个<b>廉价模型</b>（配置 router.llm-route-model，默认 deepseek/deepseek-flash）
 * 做意图分类，{@code .call().entity(RouteDecision.class)} 让 Spring AI 自动注入 JSON Schema
 * 并把返回值反序列化为 {@link RouteDecision}，拿到路线名。
 * <p>
 * 定位：级联的兜底层。只处理语义层拿不准的模糊/复合意图消息（经验占比约两成），
 * 所以每轮多花一次廉价模型调用的成本是可接受的。
 * <p>
 * 注意：路由客户端通过 {@link ModelRegistry#getRawClient} 构建（不挂工具），
 * 避免模型在分类时误触发 getCurrentTime 等工具调用。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmRouteLayer implements RouteLayer {

    private static final String FALLBACK_ROUTE = "fallback";

    private final ModelRegistry registry;
    private final ModelRouteProperties properties;

    /** 路由分类专用客户端（懒加载，双检锁） */
    private volatile ChatClient routerClient;

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public Optional<String> tryDecide(String userMessage) {
        try {
            RouteDecision decision = client().prompt()
                    .system(systemPrompt())
                    .user(userMessage)
                    .call()
                    .entity(RouteDecision.class);
            if (decision != null && isValid(decision.route())) {
                log.debug("LLM 路由决策: route={}, reason={}", decision.route(), decision.reason());
                return Optional.of(decision.route());
            }
            log.warn("LLM 返回非法路线，放行下一层处理: {}", decision);
        } catch (Exception e) {
            // 路由本身失败不能阻断主流程，降级放行（最终由 default 路线兜底）
            log.warn("LLM 路由异常，放行下一层处理: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 校验 LLM 输出：路线必须已配置且不是 fallback（fallback 仅供异常兜底，不参与正常分类）
     */
    private boolean isValid(String route) {
        return route != null && !FALLBACK_ROUTE.equals(route) && properties.getRoutes().containsKey(route);
    }

    /**
     * 分类 System Prompt：路线清单及说明来自配置（router.desc），模型只输出 JSON 不回答问题
     */
    private String systemPrompt() {
        Map<String, String> desc = properties.getRouter().getDesc();
        StringBuilder sb = new StringBuilder("你是模型路由分类器。根据用户消息选择唯一最合适的路线，只输出 JSON，不回答用户问题本身。\n可选路线：\n");
        properties.getRoutes().keySet().stream()
                .filter(route -> !FALLBACK_ROUTE.equals(route))
                .forEach(route -> sb.append("- ").append(route).append("：")
                        .append(desc.getOrDefault(route, "通用")).append('\n'));
        return sb.toString();
    }

    private ChatClient client() {
        if (routerClient == null) {
            synchronized (this) {
                if (routerClient == null) {
                    String[] target = properties.getRouter().getLlmRouteModel().split("/", 2);
                    // raw 客户端：不挂 CommonTools，防止分类时误触发工具
                    routerClient = registry.getRawClient(target[0], target[1]);
                }
            }
        }
        return routerClient;
    }
}
