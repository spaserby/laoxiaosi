package com.law.backend.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索服务
 * <p>
 * <b>检索管线</b>（对照工业界标准管线补齐）：
 * <pre>
 *   ① 稠密路粗召回：向量 KNN recallTopK=12（宽进保召回率，threshold 截断噪声）
 *   ② 稀疏路召回：  pg_trgm GIN (ILIKE) BM25 近似 bm25TopK=10（补编号等离散符号精确匹配）
 *   ③ RRF 融合：    score(d) = Σ 1/(60 + rank)，两路互补去重（条文级）
 *   ④ rerank 精排： 百炼 gte-rerank-v2 Cross-Encoder 取 topK=5（失败降级为融合序截断）
 *   ⑤ Small-to-Big：chunk 子块命中 → 按 articleId 还原整条全文注入（上下文完整性）
 * </pre>
 * <b>为什么不把 topK 直接调大</b>：噪声条目稀释注意力、撑爆上下文、诱发"硬凑依据"幻觉；
 * 宽进窄出的两阶段才是召回率与精度兼得的标准解。
 * <p>
 * <b>降级信条</b>：每一阶段失败都不阻断——BM25 失败退纯稠密路、rerank 失败退融合序、
 * Small-to-Big 失败退 chunk 文本、全链失败返回空列表（对话退化为纯 LLM）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    /** RRF 融合常数（工业界经验值） */
    static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final RerankService rerankService;
    private final LawArticleMapper lawArticleMapper;

    /**
     * 检索结构化法条引用
     *
     * @param query    检索词（打底路为 Planner 改写后的查询；工具路为模型改写词）
     * @param category 分类过滤（民事/劳动/...；null/blank/"original" 不过滤）
     * @return 精排后的命中引用列表（整条全文），不可用/无结果时返回空列表
     */
    public List<LawCitation> retrieveCitations(String query, String category) {
        if (!ragProperties.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        String filter = normalizeCategory(category);

        // ① 稠密路粗召回（分类过滤失败降级为不过滤）
        List<LawCitation> dense = searchDense(query, filter);

        // ② 稀疏路召回（BM25；索引未建等失败降级为纯稠密路）
        List<LawCitation> sparse = List.of();
        if (ragProperties.isBm25Enabled()) {
            try {
                sparse = lawArticleMapper.keywordSearch(query, ragProperties.getBm25TopK())
                        .stream().map(this::entityToCitation).toList();
            } catch (Exception e) {
                log.warn("BM25 检索失败（pg_trgm 索引可能未建），降级为纯稠密路: {}", e.getMessage());
            }
        }

        // ③ RRF 融合（条文级去重，保留最高分 chunk）
        List<LawCitation> fused = rrfFuse(dense, sparse, ragProperties.getRecallTopK());
        if (fused.isEmpty()) {
            return List.of();
        }

        // ④ rerank 精排取 topK（失败降级为融合序截断）；
        //    精排 relevance_score 回写 citation.score——前端展示的相关度是 Cross-Encoder 真实分数
        List<LawCitation> ranked = fused.stream().limit(ragProperties.getTopK()).toList();
        if (ragProperties.isRerankEnabled() && fused.size() > ragProperties.getTopK()) {
            try {
                List<RerankService.RerankHit> hits = rerankService.rerank(
                        query, fused.stream().map(LawCitation::text).toList(), ragProperties.getTopK());
                List<LawCitation> reranked = hits.stream()
                        .filter(h -> h.index() >= 0 && h.index() < fused.size())
                        .map(h -> {
                            LawCitation c = fused.get(h.index());
                            return new LawCitation(c.articleId(), c.lawName(), c.articleNo(),
                                    c.category(), h.score(), c.text(), c.chunked());
                        })
                        .toList();
                if (!reranked.isEmpty()) {
                    ranked = reranked;
                }
            } catch (Exception e) {
                log.warn("rerank 精排失败，降级为融合序取 topK: {}", e.getMessage());
            }
        }

        // ⑤ Small-to-Big：chunk 子块命中还原整条全文
        return ranked.stream().map(this::expandToFullArticle).toList();
    }

    /**
     * 检索条文文本（兼容旧调用方的便捷方法）
     */
    public List<String> retrieve(String query) {
        return retrieveCitations(query, null).stream().map(LawCitation::text).toList();
    }

    /**
     * ① 稠密路：向量 KNN 粗召回（recallTopK + threshold + 可选分类过滤）
     */
    private List<LawCitation> searchDense(String query, String filter) {
        try {
            return toCitations(search(query, filter, ragProperties.getRecallTopK()));
        } catch (Exception e) {
            if (filter != null) {
                log.warn("RAG 分类过滤检索失败，降级为不过滤: category={}, error={}", filter, e.getMessage());
                try {
                    return toCitations(search(query, null, ragProperties.getRecallTopK()));
                } catch (Exception retryError) {
                    log.warn("RAG 检索失败（Redis 向量库可能不可用），降级为纯对话: {}", retryError.getMessage());
                    return List.of();
                }
            }
            log.warn("RAG 检索失败（Redis 向量库可能不可用），降级为纯对话: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 向量库查询：topK + 相似度阈值 + 可选分类过滤
     */
    private List<Document> search(String query, String filter, int topK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                // 阈值截断——低于此相似度的召回视为"未命中"，激活落空分支
                .similarityThreshold(ragProperties.getSimilarityThreshold());
        if (filter != null) {
            builder.filterExpression("category == '" + filter + "'");
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * ③ RRF 融合（ Reciprocal Rank Fusion ）：score(d) = Σ 1/(k + rank)，k=60
     * <p>
     * 条文级去重（lawName|articleNo 为键）：同一条文的多个 chunk 只保留融合分最高的一个。
     * 包级可见 + static，供金标测试直接验证融合数学。
     */
    static List<LawCitation> rrfFuse(List<LawCitation> dense, List<LawCitation> sparse, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, LawCitation> byKey = new LinkedHashMap<>();
        accumulate(dense, scores, byKey);
        accumulate(sparse, scores, byKey);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    /** 单路贡献：rank 从 1 起，score += 1/(RRF_K + rank)；同键保留首次出现（高分路优先入 map） */
    private static void accumulate(List<LawCitation> path, Map<String, Double> scores, Map<String, LawCitation> byKey) {
        for (int i = 0; i < path.size(); i++) {
            LawCitation c = path.get(i);
            String key = c.key();
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            // 同键保留 score 更高的 chunk 文本：稠密路带真实 score，优先于稀疏路占位
            LawCitation exist = byKey.get(key);
            if (exist == null || c.score() > exist.score()) {
                byKey.put(key, c);
            }
        }
    }

    /**
     * ⑤ Small-to-Big：chunk 子块命中 → 按 articleId 回 PG 底账还原整条全文
     * （检索粒度用小块保精度，注入粒度用整条保上下文完整性）
     */
    private LawCitation expandToFullArticle(LawCitation c) {
        if (!c.chunked() || c.articleId() == null) {
            return c;
        }
        try {
            LawArticleEntity a = lawArticleMapper.findById(c.articleId());
            if (a == null) {
                return c;
            }
            return new LawCitation(a.getId(), a.getLawName(), a.getArticleNo(),
                    c.category(), c.score(), a.toEmbeddingText(), false);
        } catch (Exception e) {
            log.warn("Small-to-Big 还原失败，降级为 chunk 文本: articleId={}, error={}", c.articleId(), e.getMessage());
            return c;
        }
    }

    /** 固定文本格式兜底解析："法律名称：《X》。条文编号：Y。"（《》兼容新旧两代向量文档） */
    private static final java.util.regex.Pattern LAW_REF =
            java.util.regex.Pattern.compile("法律名称：(《?[^。]+?》?)。条文编号：([^。.]+)");

    /**
     * Document → 结构化引用：metadata 不再丢弃
     * <p>
     * RedisVectorStore 读回时自定义 metadata 可能丢失（未声明进索引 schema），
     * 兜底链：articleId 从文档 id（law:article:{id}[#cN]）解析；法名/条号从固定 text 格式解析。
     */
    private List<LawCitation> toCitations(List<Document> docs) {
        List<LawCitation> result = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Long articleId = toLong(d.getMetadata().get("articleId"));
            String lawName = asString(d.getMetadata().get("lawName"));
            String articleNo = asString(d.getMetadata().get("articleNo"));
            String category = asString(d.getMetadata().get("category"));
            if (articleId == null) {
                articleId = parseArticleId(d.getId());
            }
            if (lawName.isEmpty() || articleNo.isEmpty()) {
                java.util.regex.Matcher m = LAW_REF.matcher(d.getText() == null ? "" : d.getText());
                if (m.find()) {
                    if (lawName.isEmpty()) lawName = m.group(1);
                    if (articleNo.isEmpty()) articleNo = m.group(2);
                }
            }
            // 法名格式归一：数据层统一带《》（新底账 law_name 无书名号，旧 mock 有），展示/引用键同源
            if (!lawName.isEmpty() && !lawName.startsWith("《")) {
                lawName = "《" + lawName + "》";
            }
            result.add(new LawCitation(
                    articleId, lawName, articleNo, category,
                    d.getScore() == null ? 0.0 : d.getScore(),
                    d.getText(),
                    "1".equals(asString(d.getMetadata().get("chunked")))));
        }
        return result;
    }

    /** 文档 id → 底账主键（law:article:10247#c0 → 10247） */
    private Long parseArticleId(String docId) {
        if (docId == null) return null;
        String t = docId.startsWith("law:article:") ? docId.substring("law:article:".length()) : docId;
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 实体 → 引用（BM25 路：整条命中，chunked=false，score 占位 0） */
    private LawCitation entityToCitation(LawArticleEntity a) {
        return new LawCitation(a.getId(), a.getLawName(), a.getArticleNo(),
                a.getCategory() == null ? "" : a.getCategory(), 0.0, a.toEmbeddingText(), false);
    }

    /** 分类归一化：null/blank/"original"（Planner 未产出分类）/ "none" → 不过滤 */
    private String normalizeCategory(String category) {
        if (category == null || category.isBlank() || "original".equalsIgnoreCase(category)
                || "none".equalsIgnoreCase(category)) {
            return null;
        }
        return category.trim();
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }
}
