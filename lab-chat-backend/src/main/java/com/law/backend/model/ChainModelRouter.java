package com.law.backend.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 级联模型路由器
 * <p>
 * 按配置 legal.ai.model.router.layers 的顺序构建决策链（默认 keyword → semantic → llm），
 * 逐层询问：哪层先给出决策就用哪层，全部放行则落 default 路线。
 * <p>
 * <b>命中路线后除解析模型客户端外，还按 legal.ai.model.route-tools
 * 解析该路线的按需工具集（bean 名 → 实例）与检索预算，一并封装进 {@link RoutedClient}——
 * 路由从"选模型"升级为"选模型 + 配工具预算"。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li>成本递增的层级顺序——0 成本硬规则先筛，embedding 快筛兜中间，LLM 只处理长尾</li>
 *   <li>每层独立容错；关掉某层只需从 layers 配置移除</li>
 *   <li>三层均懒加载；工具 bean 名解析失败仅告警跳过（工具缺失不阻断对话，与全链路降级一致）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChainModelRouter implements ModelRouter {

    private static final String DEFAULT_ROUTE = "default";
    private static final String FALLBACK_ROUTE = "fallback";

    private final ModelRegistry registry;
    private final ModelRouteProperties properties;

    /** 语义层复用 RAG 的向量模型（text-embedding-v3） */
    private final EmbeddingModel embeddingModel;

    /** 按 bean 名解析路线工具集 */
    private final ApplicationContext applicationContext;

    /** 决策链（按配置顺序，懒加载） */
    private volatile List<RouteLayer> chain;

    @Override
    public RoutedClient route(String userMessage) {
        String routeName = null;
        String decidedBy = "none";
        for (RouteLayer layer : chain()) {
            Optional<String> decided = layer.tryDecide(userMessage);
            if (decided.isPresent()) {
                routeName = decided.get();
                decidedBy = layer.name();
                break;
            }
        }
        if (routeName == null) {
            routeName = DEFAULT_ROUTE;
        }
        log.info("模型路由: layer={}, route={}, message={}", decidedBy, routeName, userMessage);
        return build(routeName);
    }

    @Override
    public RoutedClient fallback() {
        log.info("切换 fallback 路线");
        return build(FALLBACK_ROUTE);
    }

    /**
     * 组装路由结果：模型客户端 + 该路线的工具集与检索预算
     */
    private RoutedClient build(String routeName) {
        String[] target = properties.resolve(routeName);
        ModelRouteProperties.RouteTools routeTools =
                properties.getRouteTools().getOrDefault(routeName, new ModelRouteProperties.RouteTools());
        return new RoutedClient(
                routeName,
                registry.getClient(target[0], target[1]),
                resolveTools(routeName, routeTools),
                routeTools.getMaxToolCalls());
    }

    /**
     * 工具 bean 名 → 实例：解析失败告警跳过，不阻断对话
     */
    private List<Object> resolveTools(String routeName, ModelRouteProperties.RouteTools routeTools) {
        if (routeTools.getTools().isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> tools = new ArrayList<>();
        for (String beanName : routeTools.getTools()) {
            try {
                tools.add(applicationContext.getBean(beanName));
            } catch (Exception e) {
                log.warn("路线 {} 的工具 bean 不存在，已跳过: {}（检查 legal.ai.model.route-tools 配置）",
                        routeName, beanName);
            }
        }
        return List.copyOf(tools);
    }

    private List<RouteLayer> chain() {
        if (chain == null) {
            synchronized (this) {
                if (chain == null) {
                    chain = buildChain();
                }
            }
        }
        return chain;
    }

    /**
     * 按配置顺序构建决策链；移除配置项即禁用对应层（降级路由只改 yml）
     */
    private List<RouteLayer> buildChain() {
        List<RouteLayer> layers = new ArrayList<>();
        for (String name : properties.getRouter().getLayers()) {
            switch (name) {
                case "keyword" -> layers.add(new KeywordRouteLayer(properties));
                case "semantic" -> layers.add(new SemanticRouteLayer(embeddingModel, properties));
                case "llm" -> layers.add(new LlmRouteLayer(registry, properties));
                default -> log.warn("忽略未知路由层配置: {}", name);
            }
        }
        log.info("路由决策链初始化完成: {}", layers.stream().map(RouteLayer::name).toList());
        return List.copyOf(layers);
    }
}
