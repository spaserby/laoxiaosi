package com.law.backend.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具
 * <p>
 * <b>模式：LLM-as-Search-Tool</b>。主模型调用 webSearch 工具时，工具内部再调用一个
 * <b>自带联网能力的子模型</b>（DeepSeek v4 flash + enable_search；
 * 此前为百炼 qwen），用"法律资料检索员"提示词
 * 约束其检索偏好与输出格式，返回带来源标注的摘要文本。
 * <p>
 * <b>为什么放弃搜索 API（Tavily/博查）改用联网大模型</b>：
 * <ul>
 *   <li>价格：博查 0.03 元/次；qwen-plus 按 token 计费一次检索不到一分钱，且
 *       复用已有百炼 Key，零新增账号</li>
 *   <li>开发难度：一个 HTTP POST（body 加 enable_search 开关）即完成</li>
 * </ul>
 * <b>代价（知情选择）</b>：enable_search 搜索范围是全网、无法像搜索 API 那样在 API 层
 * 硬过滤域名——"只采信官方来源"从硬约束降级为提示词软约束（子模型输出必须带来源网址，
 * 供用户核验）。联网定位是辅助参考，此精度足够。
 * <p>
 * <b>为什么手写 HTTP 而不走 Spring AI ChatModel</b>：enable_search 是 DashScope 的
 * 私有 body 参数，Spring AI 的 OpenAiChatOptions 不透传此类扩展字段，
 * 故用 {@link RestClient} 直调 OpenAI 兼容端点。
 * <p>
 * 门面行为与 searchLaw 同构：共享预算扣减 / 降级文案 / 日志观测，由 route-tools
 * 决定挂载路线（当前仅 legal/fallback）。
 */
@Slf4j
@Component
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchTool {

    /**
     * 子模型提示词（法律资料检索员）——"只采信官方来源"软约束的载体：
     * 搜索范围虽是全网，但要求子模型仅把官方权威来源作为结论依据，
     * 自媒体内容至多做线索；且必须逐条标注来源网址，供最终回答引用与用户核验。
     */
    private static final String SEARCH_ASSISTANT_PROMPT = "你是法律信息联网检索员。收到检索请求后联网搜索并遵守："
            + "1) 只采信官方权威来源（gov.cn 及其子站、法院官网、中国裁判文书网、国家法律法规数据库等），"
            + "自媒体和商业网站内容仅可作为线索，不得作为结论依据；"
            + "2) 以条目返回，每条包含：信息摘要、来源网站名称、来源网址、信息日期；"
            + "3) 优先返回最新且权威的信息，最多 5 条；"
            + "4) 若官方来源未检索到，直接回答'官方渠道未检索到相关信息'，禁止编造来源或内容；"
            + "5) 不要客套，直接输出条目。";

    private final WebSearchProperties properties;
    private final RestClient restClient;

    /** 显式构造：RestClient 需读取配置的 base-url 与超时，无法用 @RequiredArgsConstructor */
    public WebSearchTool(WebSearchProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(properties.getLlm().getTimeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getLlm().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    /**
     * 联网搜索：本地条文检索（searchLaw）未命中、或问题涉及时效性信息时由主模型调用
     */
    @Tool(description = "联网搜索最新法律信息。当问题涉及新颁布/修订的法律法规、近期政策变化、"
            + "司法案例、本地知识库未覆盖的内容，或用户明确要求联网时调用。"
            + "返回结果已由检索员筛选并标注官方来源；回答时必须标注每条信息的来源网址。"
            + "一般的法律条文问题应优先调用 searchLaw 检索本地知识库。")
    public String webSearch(
            @ToolParam(description = "检索关键词。提炼为规范的法律术语，"
                    + "例如'2026年经济补偿金个税政策'可检索'经济补偿金 个人所得税 政策'") String query,
            ToolContext toolContext) {
        // 总开关关闭：降级为本地知识（与 searchLaw 的 rag.enabled 开关策略对齐）
        if (!properties.isEnabled()) {
            return "联网搜索未启用，请基于本地知识库与通用知识回答，并提醒用户答案可能不含最新动态。";
        }
        // 共享工具预算：与 searchLaw 从同一预算扣减，防止先查本地再联网双重失控
        ToolBudget budget = (ToolBudget) toolContext.getContext().get(ToolBudget.KEY);
        if (budget != null && !budget.tryAcquire()) {
            log.info("工具预算已耗尽: query={}, 已用={}/{}", query, budget.usedCount(), budget.maxCalls());
            // 连续拒绝 ≥3 次升级强制收敛指令
            return budget.denialCount() >= ToolBudget.HARD_DENY_AFTER
                    ? "检索次数已达本轮上限且连续多次调用被拒。【强制】立即停止一切工具调用，"
                            + "基于已检索到的信息直接生成完整回答。"
                    : "本轮检索工具预算（" + budget.maxCalls() + " 次）已用完，不要再调用 searchLaw 或 webSearch，"
                            + "请基于已检索到的信息直接作答。";
        }
        try {
            String answer = callOnlineModel(query);
            log.info("webSearch 工具调用: query={}, 子模型={}, 返回={} 字",
                    query, properties.getLlm().getModel(), answer.length());
            return answer;
        } catch (Exception e) {
            // 联网搜索失败不阻断回答：降级为本地知识（与 searchLaw 降级策略对齐）
            log.warn("webSearch 调用失败，降级为本地知识: query={}, error={}", query, e.getMessage());
            return "联网搜索暂时不可用，请基于本地知识库与通用知识回答，并提醒用户答案仅供参考。";
        }
    }

    /**
     * 直调 DeepSeek OpenAI 兼容端点：body 顶层加 enable_search 开启原生联网搜索
     *（实测 deepseek-v4-flash 支持该参数，与 DashScope 同名；Spring AI 的
     * OpenAiChatOptions 不透传此类扩展字段，故手写 RestClient）
     */
    private String callOnlineModel(String query) {
        Map<String, Object> body = Map.of(
                "model", properties.getLlm().getModel(),
                "temperature", properties.getLlm().getTemperature(),
                "enable_search", true,
                "messages", List.of(
                        Map.of("role", "system", "content", resolvePrompt()),
                        Map.of("role", "user", "content", query)));
        Map<?, ?> response = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(Map.class);
        return extractContent(response);
    }

    /** 配置了自定义提示词则用之，否则用默认检索员提示词 */
    private String resolvePrompt() {
        String custom = properties.getLlm().getPrompt();
        return (custom == null || custom.isBlank()) ? SEARCH_ASSISTANT_PROMPT : custom;
    }

    /** 从 OpenAI 兼容响应中取 choices[0].message.content，异常结构抛给上层降级 */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("联网子模型返回空响应");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("联网子模型响应无 choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = message == null ? null : (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("联网子模型响应无内容");
        }
        return content;
    }
}
