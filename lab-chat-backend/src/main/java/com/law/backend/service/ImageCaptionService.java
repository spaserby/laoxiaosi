package com.law.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片 caption 服务：VL 模型一句话描述，供记忆占位与后续轮轻量召回
 * <p>
 * 手写 HTTP 直调 DashScope compatible-mode（与 WebSearchTool 同款模式）：
 * Spring AI 的 ChatClient 多模态消息装配较重，caption 是单发短调用，直调更可控。
 * <p>
 * 成本闸由调用方实施：每文档限 captionLimit 张 + 并行 + 超时降级（本服务只管单张）。
 */
@Slf4j
@Service
public class ImageCaptionService {

    private final RestClient restClient;
    private final String model;

    public ImageCaptionService(com.law.backend.model.ModelRouteProperties routeProperties,
                               FileProperties fileProperties) {
        // 端点/密钥跟随 vl-provider（模型名与端点必须同族，
        // 如 deepseek-v4-flash 配 deepseek 端点；qwen-vl-max 配 vl/dashscope 端点）
        com.law.backend.model.ModelRouteProperties.Provider provider =
                routeProperties.getProviders().get(fileProperties.getVlProvider());
        if (provider == null) {
            throw new IllegalArgumentException("未配置多模态 provider: legal.ai.model.providers."
                    + fileProperties.getVlProvider());
        }
        this.restClient = RestClient.builder()
                .baseUrl(provider.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
        this.model = fileProperties.getVlModel();
    }

    /**
     * 一句话描述图片（≤50 字）；失败返回空串（调用方降级）
     */
    public String caption(byte[] bytes, String mime) {
        try {
            String b64 = Base64.getEncoder().encodeToString(bytes);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 80,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text",
                                            "用一句话（50字内）概括这张图的内容，服务于法律咨询上下文（如合同条款截图/证据照片/表格）。只输出描述本身。"),
                                    Map.of("type", "image_url", "image_url",
                                            Map.of("url", "data:" + mime + ";base64," + b64))))));
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restClient.post()
                    // provider baseUrl 不带 /v1（Spring AI 约定），手写 RestClient 需自拼
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) return "";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) return "";
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            return content == null ? "" : content.toString().trim();
        } catch (Exception e) {
            log.warn("caption 生成失败（降级为空）: {}", e.getMessage());
            return "";
        }
    }
}
