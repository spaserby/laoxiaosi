package com.law.backend.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RAG 配置属性
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.rag:
 *   enabled: true            # RAG 总开关，false 时检索直接返回空（降级为纯对话）
 *   top-k: 3                 # 检索返回最相关的 K 条条文
 *   import-on-startup: true  # 启动时自动执行 MySQL → 向量库增量同步
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.rag")
public class RagProperties {

    /** RAG 总开关，false 时检索降级返回空 */
    private boolean enabled = true;

    /** 检索返回最相关的 K 条文档，默认 5 */
    private int topK = 5;

    /**
     * 粗召回条数：向量路宽进保证召回率，交给 rerank 精排筛到 topK。
     * 大厂标准：粗召回 10~20 + Cross-Encoder 精排取 3~5，而非把 topK 直接调大塞 Prompt（噪声稀释注意力）
     */
    private int recallTopK = 12;

    /** rerank 精排开关：失败自动降级为按向量分数排序取 topK */
    private boolean rerankEnabled = true;

    /** rerank 模型（百炼，复用 DASHSCOPE Key） */
    private String rerankModel = "gte-rerank-v2";

    /** rerank API Key（默认复用百炼 Key 环境变量） */
    private String rerankApiKey = "";

    /** rerank 端点（百炼 text-rerank 服务） */
    private String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** BM25 混合检索开关：MySQL FULLTEXT ngram 补向量路对条文编号等离散符号的召回短板 */
    private boolean bm25Enabled = true;

    /** BM25 路召回条数 */
    private int bm25TopK = 10;

    /** 长条文分块阈值：content 超过此长度按句拆子块（Small-to-Big：块检索、整条还原） */
    private int chunkMaxLength = 480;

    /**
     * 相似度阈值：低于此值的召回直接丢弃。
     * 无阈值时 topK 永远返回满 K 条（全库最不相关的也塞进 Prompt 成噪声），
     * 且"检索落空→建议咨询律师"分支永远不触发；0.55 为 text-embedding-v3 中文经验值。
     */
    private double similarityThreshold = 0.55;

    /**
     * 强制检索打底的路线名单（
     * 取代 buildSystemPrompt 里硬编码的 "legal" 判断）
     */
    private List<String> groundedRoutes = List.of("legal");

    /** 启动时是否自动执行知识库增量同步 */
    private boolean importOnStartup = true;
}
