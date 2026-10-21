package com.law.backend.eval.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spaserby.aikit.eval.entry.TestTarget;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 可选 eval-kit profile 的入口适配器：把 {@link TestTarget} 接到后端 SSE 接口。
 *
 * <p>为什么走 HTTP 而不是 @SpringBootTest 注入 ChatService：验收要证明的是
 * "对外交付的那个系统"，与甲方在验收会上使用的调用方式保持一致；
 * 同时评测进程与被测进程隔离，被测方崩了不会把评测一起带崩。</p>
 */
public final class HttpChatTarget {

    /** 答案正文所在的事件名（其余 stage/reasoning/citation/quota/error 不计入答案） */
    private static final String EVENT_TOKEN = "token";

    /** 单条回答最多读取的 token 事件数，防止异常流无限增长 */
    private static final int MAX_TOKEN_EVENTS = 6000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String endpoint;

    private final Duration timeout;

    private final HttpClient client;

    public HttpChatTarget(String baseUrl, Duration timeout) {
        this.endpoint = baseUrl + "/chat/stream";
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 默认指向本机 8082，单题最长等待 120 秒（与既有端到端评估一致） */
    public static HttpChatTarget localhost() {
        return new HttpChatTarget("http://localhost:8082", Duration.ofSeconds(120));
    }

    /** 交给 EvaluationRunner 的入口抽象 */
    public TestTarget asTestTarget() {
        return this::ask;
    }

    public String ask(String question) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", "eval-" + UUID.randomUUID());
        payload.put("userMessage", question);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // 匿名会话归属凭据：每题独立，避免污染真实会话记忆与配额归属
                .header("X-Guest-Key", "eval-" + UUID.randomUUID())
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("被测接口返回 HTTP " + response.statusCode() + "，问题：" + question);
        }
        return collectAnswer(response.body());
    }

    /** 解析 SSE：按 event/data 成对读取，只拼接 token 事件的正文 */
    private String collectAnswer(InputStream stream) throws Exception {
        StringBuilder answer = new StringBuilder();
        String event = null;
        int tokenEvents = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                }
                else if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (EVENT_TOKEN.equals(event) && !data.isEmpty()) {
                        answer.append(decode(data));
                        if (++tokenEvents >= MAX_TOKEN_EVENTS) {
                            break;
                        }
                    }
                }
                else if (line.isEmpty()) {
                    event = null;
                }
            }
        }
        return answer.toString();
    }

    /** 服务端用 sseData() 把 token 做了一次 JSON 编码，这里还原为纯文本 */
    private String decode(String data) throws Exception {
        if (data.startsWith("\"")) {
            return OBJECT_MAPPER.readValue(data, String.class);
        }
        return data;
    }
}
