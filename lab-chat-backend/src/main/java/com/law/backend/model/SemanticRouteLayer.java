package com.law.backend.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义路由层
 * <p>
 * 原理：
 * <ol>
 *   <li>首次使用时把配置里每条路线的示例语料（legal.ai.model.examples）向量化，
 *       平均得到该路线的"质心"向量并缓存</li>
 *   <li>请求进来：用户消息向量化 → 与各路线质心算余弦相似度 → 最高分 ≥ 阈值即命中</li>
 * </ol>
 * <p>
 * 定位：级联中间层，处理绝大多数意图清晰的消息。无 LLM 调用、仅一次 embedding，
 * 成本远低于 LLM 层；拿不准（低于阈值）时放行给 LLM 层。
 * <p>
 * 容错：embedding 端点异常/未配置语料时返回空放行，不阻断主流程（与 RAG 降级同思路）。
 */
@Slf4j
@RequiredArgsConstructor
public class SemanticRouteLayer implements RouteLayer {

    private final EmbeddingModel embeddingModel;
    private final ModelRouteProperties properties;

    /** 路线名 → 质心向量（懒加载，双检锁；启动后只算一次） */
    private volatile Map<String, float[]> centroids;

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public Optional<String> tryDecide(String userMessage) {
        try {
            Map<String, float[]> map = ensureCentroids();
            if (map.isEmpty()) {
                return Optional.empty();
            }
            float[] query = embeddingModel.embed(userMessage);
            String best = null;
            double bestScore = -1;
            for (Map.Entry<String, float[]> entry : map.entrySet()) {
                double score = cosine(query, entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
            double threshold = properties.getRouter().getSemanticThreshold();
            if (best != null && bestScore >= threshold) {
                log.debug("语义快筛命中: route={}, score={}", best, bestScore);
                return Optional.of(best);
            }
            log.debug("语义快筛未达阈值: best={}, score={}, threshold={}", best, bestScore, threshold);
        } catch (Exception e) {
            log.warn("语义路由异常，放行下一层处理: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Map<String, float[]> ensureCentroids() {
        if (centroids == null) {
            synchronized (this) {
                if (centroids == null) {
                    centroids = computeCentroids();
                }
            }
        }
        return centroids;
    }

    /**
     * 各路线示例语料向量化后求平均，得到路线质心
     */
    private Map<String, float[]> computeCentroids() {
        Map<String, float[]> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : properties.getExamples().entrySet()) {
            List<String> examples = entry.getValue();
            if (examples == null || examples.isEmpty()) {
                continue;
            }
            try {
                float[] centroid = null;
                for (String example : examples) {
                    float[] vec = embeddingModel.embed(example);
                    if (centroid == null) {
                        centroid = vec.clone();
                    } else {
                        for (int i = 0; i < centroid.length; i++) {
                            centroid[i] += vec[i];
                        }
                    }
                }
                for (int i = 0; i < centroid.length; i++) {
                    centroid[i] /= examples.size();
                }
                result.put(entry.getKey(), centroid);
            } catch (Exception e) {
                log.warn("路线质心计算失败，该路线不参与语义快筛: route={}, error={}", entry.getKey(), e.getMessage());
            }
        }
        log.info("语义路由质心构建完成: routes={}", result.keySet());
        return result;
    }

    /**
     * 余弦相似度（维度不一致时返回 -1 视为不匹配）
     */
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-9);
    }
}
