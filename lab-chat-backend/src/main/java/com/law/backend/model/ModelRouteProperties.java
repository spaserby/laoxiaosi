package com.law.backend.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路模型配置
 * <p>
 * <b>两层结构</b>：
 * <ul>
 *   <li>providers —— 模型"端点"层：每个端点 = base-url + api-key，同一端点可挂多个模型名
 *       （如 DeepSeek 官方端点下有 flash 和 v4-pro 两个模型，共用一个 Key）</li>
 *   <li>routes —— 业务"路线"层：每条路线指向 provider/model，Router 按规则选路线，
 *       出错自动走 fallback 路线</li>
 * </ul>
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.model:
 *   providers:
 *     deepseek:
 *       base-url: https://api.deepseek.com
 *       api-key: ${DEEPSEEK_API_KEY:...}
 *     qwen:
 *       base-url: https://token-plan.../compatible-mode
 *       api-key: ${AI_API_KEY:...}
 *   routes:
 *     default: deepseek/deepseek-flash
 *     reasoning: deepseek/deepseek-v4-pro
 *     fallback: qwen/qwen3.8-max
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.model")
public class ModelRouteProperties {

    /** 端点层：key = 端点名（如 deepseek / qwen） */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** 路线层：key = 路线名（default / reasoning / cheap / legal / fallback），value = "端点名/模型名" */
    private Map<String, String> routes = new LinkedHashMap<>();

    /** 路由关键词：路线名 → 命中关键词列表（keyword 层硬规则） */
    private Map<String, List<String>> keywords = new LinkedHashMap<>();

    /** 级联路由编排配置 */
    private Router router = new Router();

    /** 语义路由示例语料：路线名 → 示例句列表（semantic 层向量化求平均得到路线质心） */
    private Map<String, List<String>> examples = new LinkedHashMap<>();

    /**
     * 路线差异化工具预算：路线名 → 工具 bean 名列表 + 检索调用上限。
     * 不同档位路线挂不同工具集：cheap 闲聊不挂检索工具，legal 专业路线全量工具更高预算。
     */
    private Map<String, RouteTools> routeTools = new LinkedHashMap<>();

    /** 审校重试追加的工具预算（重试是质量门触发的系统行为；与 route-tools 同级配置） */
    private int retryBudgetGrant = 8;

    /**
     * 路线级 temperature 差异化（未配置的路线用 provider 默认值）：
     * legal 低温稳定（0.3~0.5）/ 闲聊高温活泼（0.9）/ 推理中温
     */
    private Map<String, Double> temperatures = new LinkedHashMap<>();

    /** 单个模型端点配置 */
    @Data
    public static class Provider {
        /** OpenAI 兼容端点，不带末尾 /v1 */
        private String baseUrl;
        /** 该端点的 API Key，建议环境变量注入 */
        private String apiKey;
        /** 采样温度 */
        private double temperature = 0.7;
    }

    /**
     * 解析路线指向：如 "deepseek/deepseek-flash" → [deepseek, deepseek-flash]
     */
    public String[] resolve(String routeName) {
        String target = routes.get(routeName);
        if (target == null || !target.contains("/")) {
            throw new IllegalArgumentException("未配置模型路线: " + routeName);
        }
        return target.split("/", 2);
    }

    /** 级联路由编排配置 */
    @Data
    public static class Router {
        /** 决策链顺序，取值 keyword/semantic/llm；从列表移除即禁用该层（降级路由只改配置） */
        private List<String> layers = List.of("keyword", "semantic", "llm");
        /** LLM 路由层使用的廉价分类模型：provider/model 格式 */
        private String llmRouteModel = "deepseek/deepseek-flash";
        /** 语义快筛余弦相似度阈值：最高分 ≥ 阈值才判定命中，否则放行 LLM 层 */
        private double semanticThreshold = 0.75;
        /** 路线说明：路线名 → 描述（拼进 LLM 路由层的分类 System Prompt） */
        private Map<String, String> desc = new LinkedHashMap<>();
    }

    /** 单条路线的工具预算配置 */
    @Data
    public static class RouteTools {
        /** 按需挂载的工具 bean 名列表（如 searchLawTool / legalTools）；空列表 = 该路线无按需工具 */
        private List<String> tools = new ArrayList<>();
        /** 本轮 searchLaw 检索调用上限（ReAct 循环停止条件，0 = 相当于禁用检索） */
        private int maxToolCalls = 3;
    }
}
