package com.law.backend.model;

import com.law.backend.tool.CommonTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表
 * <p>
 * <b>构建策略</b>：
 * <ul>
 *   <li>一个 provider（端点）只建一个 {@link OpenAiApi}（base-url + api-key 相同的请求复用连接层）</li>
 *   <li>一个 provider/model 组合建一个 {@link ChatClient}，懒加载 + 缓存
 *       （同一端点的不同模型名共享 OpenAiApi，如 DeepSeek 端点下的 flash 和 v4-pro）</li>
 * </ul>
 * <p>
 * <b>为什么不用 Spring 多 Bean</b>：模型清单来自配置（legal.ai.model.routes），
 * 数量和组合不固定，手动 @Bean 无法枚举；注册表把"Bean 装配"换成"运行时工厂"，
 * 加模型只改 yml，不动代码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ModelRouteProperties.class)
public class ModelRegistry {

    private final ModelRouteProperties properties;

    /** 通用工具（时间/计算）：构建每个客户端时全局挂载，所有路线生效 */
    private final CommonTools commonTools;

    /** 端点名 → OpenAiApi（同端点复用） */
    private final Map<String, OpenAiApi> apiCache = new ConcurrentHashMap<>();

    /** 端点名 → 模型名 → ChatClient */
    private final Map<String, Map<String, ChatClient>> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取指定端点 + 模型的 ChatClient（懒加载，首次访问时构建并缓存，默认挂载通用工具）
     *
     * @param providerName 端点名（对应 legal.ai.model.providers 的 key）
     * @param modelName    模型名（每次请求以 defaultOptions 携带）
     */
    public ChatClient getClient(String providerName, String modelName) {
        return getClient(providerName, modelName, true);
    }

    /**
     * 获取不挂载任何工具的客户端
     */
    public ChatClient getRawClient(String providerName, String modelName) {
        return getClient(providerName, modelName, false);
    }

    private ChatClient getClient(String providerName, String modelName, boolean attachTools) {
        String cacheKey = attachTools ? modelName : modelName + "#raw";
        return clientCache
                .computeIfAbsent(providerName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(cacheKey, key -> build(providerName, modelName, attachTools));
    }

    private ChatClient build(String providerName, String modelName, boolean attachTools) {
        ModelRouteProperties.Provider provider = properties.getProviders().get(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("未配置模型端点: legal.ai.model.providers." + providerName);
        }
        OpenAiApi api = apiCache.computeIfAbsent(providerName, k -> OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .build());

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(provider.getTemperature())
                        .build())
                .build();

        log.info("构建模型客户端: {}/{} attachTools={}", providerName, modelName, attachTools);
        // 对话客户端：通用工具全局挂载；
        // 领域工具（LegalTools）仍由 ChatService 按需 .tools() 挂载。
        // 路由分类等场景用 attachTools=false 构建纯客户端，防止误触发工具
        if (!attachTools) {
            return ChatClient.create(chatModel);
        }
        return ChatClient.builder(chatModel)
                .defaultTools(commonTools)
                .build();
    }
}
