package com.law.backend.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank 精排服务
 * <p>
 * <b>为什么需要精排</b>：向量召回是 Bi-Encoder（query/doc 各自独立编码算余弦），
 * 无法建模词级交互，精度天花板低；Cross-Encoder 把 query 与 doc 拼在一起送模型
 * 输出相关性分数，精度高但慢——所以工业界标准是"粗召回宽进 + 精排窄出"两阶段。
 * <p>
 * <b>实现</b>：百炼 {@code gte-rerank-v2}（复用 DASHSCOPE_API_KEY，零新增账号），
 * 照抄 WebSearchTool 的手写 HTTP 直调模式（Spring AI 无 rerank 自动装配）。
 * <p>
 * <b>降级信条</b>：rerank 失败抛异常，由调用方捕获后降级为按向量分数排序取 topK——
 * 锦上添花的精排层绝不能把整条 RAG 链路带崩。
 */
@Slf4j
@Service
public class RerankService {

    private final RagProperties properties;
    private final RestClient restClient;

    public RerankService(RagProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /** 精排结果：候选下标 + Cross-Encoder 相关性分数 */
    public record RerankHit(int index, double score) {
    }

    /**
     * 对候选文档精排，返回按相关性降序的结果（下标 + 分数）
     *
     * @param query 检索词
     * @param docs  候选文档文本（粗召回结果）
     * @param topN  精排后保留条数
     * @return 精排结果列表（index 指向入参下标，score 为 relevance_score）
     * @throws Exception 调用失败由调用方降级
     */
    @SuppressWarnings("unchecked")
    public List<RerankHit> rerank(String query, List<String> docs, int topN) {
        Map<String, Object> body = Map.of(
                "model", properties.getRerankModel(),
                "input", Map.of("query", query, "documents", docs),
                "parameters", Map.of("top_n", Math.min(topN, docs.size()), "return_documents", false));
        Map<String, Object> response = restClient.post()
                .uri(properties.getRerankUrl())
                .header("Authorization", "Bearer " + properties.getRerankApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("output") == null) {
            throw new IllegalStateException("rerank 响应缺少 output");
        }
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("rerank 响应缺少 results");
        }
        List<RerankHit> hits = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            Object idx = r.get("index");
            Object sc = r.get("relevance_score");
            if (idx instanceof Number n) {
                hits.add(new RerankHit(n.intValue(), sc instanceof Number s ? s.doubleValue() : 0.0));
            }
        }
        return hits;
    }
}
