package com.law.backend.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联网搜索配置属性
 * <p>
 * <b>选型演进</b>：最初接 Tavily 搜索 API（域名白名单硬过滤），后因价格与国内可用性改为
 * "联网大模型作为检索工具"——百炼 qwen 系列 + enable_search 开关，复用百炼 API Key，
 * 无需注册任何搜索服务。代价是搜索范围无法在 API 层硬限定域名，"只采信官方来源"
 * 由子模型提示词软约束（输出必须带来源网址，供用户核验）。
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.web-search:
 *   enabled: true          # 总开关，false 时 webSearch 工具返回降级文案（不联网）
 *   llm:
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *     api-key: ${DASHSCOPE_API_KEY:...}   # 复用百炼 Key（与 embedding 同一套）
 *     model: qwen-plus     # 支持联网的子模型（enable_search）
 *     timeout-seconds: 30
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.web-search")
public class WebSearchProperties {

    /** 联网搜索总开关，false 时工具直接返回降级文案（模型基于本地知识作答） */
    private boolean enabled = false;

    /** 联网子模型配置 */
    private Llm llm = new Llm();

    @Data
    public static class Llm {

        /**
         * DashScope OpenAI 兼容端点（手写 HTTP 直调，需带完整 /v1 路径，
         * 与 Spring AI openai.base-url 不带 /v1 的约定不同）
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        /** 百炼 API Key：默认复用 embedding 的同一套 Key（环境变量 DASHSCOPE_API_KEY） */
        private String apiKey = "";

        /** 联网子模型：需为支持 enable_search 的 qwen 系列（qwen-plus/turbo/max） */
        private String model = "qwen-plus";

        /** 子模型温度：检索任务要稳定，低温 */
        private double temperature = 0.1;

        /** HTTP 超时（秒）：联网检索含搜索耗时，比普通对话留更宽 */
        private int timeoutSeconds = 30;

        /**
         * 子模型提示词（法律资料检索员）：可配置覆盖。默认值见 WebSearchTool#SEARCH_ASSISTANT_PROMPT，
         * 约束：只采信官方权威来源 / 逐条标注来源网址与日期 / 查不到必须明说禁止编造。
         */
        private String prompt;
    }
}
