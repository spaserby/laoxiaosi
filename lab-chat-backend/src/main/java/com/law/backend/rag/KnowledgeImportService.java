package com.law.backend.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库导入管道（PG 底账 + MD5 指纹增量同步）
 * <p>
 * <b>架构定位</b>：
 * <pre>
 *   PG law_article（唯一事实源/底账）
 *        │  导入管道（本类）：全量扫描 → MD5 对比 → 向量化
 *        ▼
 *   PG vector_store（pgvector 检索索引，可随时用底账重建）
 * </pre>
 * <p>
 * <b>增量同步设计</b>（替代早期"一次性导入"）：
 * <ul>
 *   <li>每条条文算 MD5 指纹，与 PG 指纹底账表 {@code rag_ledger}（id → md5;docIds）对比，
 *       得到<b>新增/变更/删除</b>三态差异，只对差异部分做向量化写入，省 embedding 开销</li>
 *   <li>向量文档用<b>确定性 ID</b> {@code law:article:{id}}，
 *       条文变更时可先删旧向量再写新的，删除时可精准回收，检索结果不会残留脏数据</li>
 *   <li>逻辑删除（deleted=1）的条文从向量库移除；物理数据始终留在 PG 底账</li>
 * </ul>
 * <p>
 * <b>每批 embedding 写入后立即落 ledger checkpoint（条文粒度），
 * 中断/失败后重点按钮从断点继续（已 checkpoint 的条文 MD5 命中跳过），
 * 进度写 Redis {@code admin:sync:progress} 供管理端轮询（多实例共享）。
 * <p>
 * <b>降级设计</b>：导入失败（PG/向量库不可用）仅告警不阻断启动，检索侧会降级为空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
public class KnowledgeImportService {

    /** 同步进度 Hash Key（与 AdminController 共享：processed/total） */
    private static final String PROGRESS_KEY = "admin:sync:progress";

    /** 单批 embedding 文档数上限（DashScope text-embedding-v3 批量上限 10 条/请求） */
    private static final int BATCH = 10;

    /** 向量文档 ID 前缀：law:article:{id}，确定性 ID 是增量同步的前提 */
    private static final String DOC_ID_PREFIX = "law:article:";

    private final VectorStore vectorStore;
    private final RedissonClient redissonClient;
    private final RagProperties ragProperties;
    private final LawArticleMapper lawArticleMapper;
    private final RagLedgerMapper ledgerMapper;

    /**
     * embedding 模型名参与 MD5 指纹——换模型后全部指纹失配，
     * 自动触发全量重建（防新旧模型向量混在同一相似度空间，检索质量静默劣化）
     */
    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.embedding.options.model:unknown}")
    private String embeddingModel;

    /** 待同步条文（diff 产物：id + 新指纹值 + 向量文档列表） */
    private record ArticleSync(String idStr, String ledgerValue, List<Document> docs) {
    }

    /**
     * 同步清单（管理端勾选界面用）：每个法名一行，含条数与已向量化条数。
     */
    public List<Map<String, Object>> syncScope() {
        return lawArticleMapper.listSyncScope();
    }

    /**
     * 应用启动完成后自动执行增量同步（受 import-on-startup 开关控制）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartup() {
        if (!ragProperties.isImportOnStartup()) {
            log.info("RAG 启动导入已关闭（legal.ai.rag.import-on-startup=false）");
            return;
        }
        syncLedgerToVector();
    }

    /**
     * 增量同步管道（全量）：PG 底账 → 向量库（新增/变更/删除三态）
     *
     * @return null = 成功；非 null = 错误信息
     */
    public String syncLedgerToVector() {
        return syncLedgerToVector(null);
    }

    /**
     * 增量同步管道（可按法名勾选作用域）：PG 底账 → 向量库（新增/变更/删除三态）
     *
     * @param lawNames 只同步这些法律；null/空 = 全量
     * @return null = 成功；非 null = 错误信息
     */
    public String syncLedgerToVector(List<String> lawNames) {
        boolean scoped = lawNames != null && !lawNames.isEmpty();
        try {
            // 1. 从 PG 底账读取有效条文（deleted=0）；勾选同步时限定在所选法名内
            List<LawArticleEntity> articles = scoped
                    ? lawArticleMapper.findAllActiveByLawNames(lawNames)
                    : lawArticleMapper.findAllActive();
            log.info("PG 底账扫描完成, 有效条文数={}, 作用域={}", articles.size(),
                    scoped ? lawNames.size() + " 部法律" : "全量");

            // 2. 读取指纹底账（PG rag_ledger：多实例共享 + 持久化，Redis flush 不再导致全量重嵌）
            Map<String, String> oldHashes = new HashMap<>();
            for (RagLedgerMapper.LedgerRow row : ledgerMapper.findAll()) {
                oldHashes.put(row.articleId(), row.ledgerValue());
            }

            // 3. 差异计算：逐条算 MD5（文本 + 模型名盐），分出 新增/变更（待写入）与 删除（待回收）
            //    ledger value 格式 "md5;docId1,docId2,..."（长条文分块后一条条文多个向量文档）
            List<ArticleSync> pending = new ArrayList<>();
            Map<String, String> newHashes = new HashMap<>();
            for (LawArticleEntity article : articles) {
                String idStr = String.valueOf(article.getId());
                String hash = md5(article.toEmbeddingText() + "|" + embeddingModel);
                List<Document> docs = toDocuments(article);
                String docIds = docs.stream().map(Document::getId).reduce((a, b) -> a + "," + b).orElse("");
                String ledgerValue = hash + ";" + docIds;
                newHashes.put(idStr, ledgerValue);
                String old = oldHashes.get(idStr);
                if (old == null || !old.startsWith(hash + ";")) {
                    // 新增或内容变更 → 先按旧 docId 列表回收旧向量（避免同一条文新旧多份留在索引里）
                    if (old != null) {
                        vectorStore.delete(parseDocIds(idStr, old));
                    }
                    pending.add(new ArticleSync(idStr, ledgerValue, docs));
                }
            }
            Set<String> toDelete = new HashSet<>(oldHashes.keySet());
            if (scoped) {
                // ★ 删除回收必须限定在同一作用域内：否则未勾选法条的指纹不在 newHashes 里，
                //   会被判成"底账已删"而整体回收掉向量（勾选同步最危险的坑）
                toDelete.retainAll(lawArticleMapper.findIdsByLawNames(lawNames)
                        .stream().map(String::valueOf).collect(Collectors.toSet()));
            }
            toDelete.removeAll(newHashes.keySet());   // 底账有、PG 已删/逻辑删 → 回收向量

            // 4. 执行同步：先回收删除的，再分批写入新增/变更的
            if (!toDelete.isEmpty()) {
                List<String> deleteIds = toDelete.stream()
                        .flatMap(id -> parseDocIds(id, oldHashes.get(id)).stream()).toList();
                vectorStore.delete(deleteIds);
                ledgerMapper.deleteAll(new ArrayList<>(toDelete));
                log.info("知识库同步: 删除过期向量 {} 个文档", deleteIds.size());
            }
            // 分批续传：buffer 满 BATCH 即写 embedding + 落 ledger checkpoint（条文粒度），
            // 中断后重点时已 checkpoint 条文 MD5 命中自动跳过——断点即 ledger
            Map<Object, Object> progress = redissonClient.getMap(PROGRESS_KEY);
            progress.putAll(Map.of("processed", "0", "total", String.valueOf(pending.size())));
            int processed = 0;
            List<Document> buffer = new ArrayList<>();
            List<RagLedgerMapper.LedgerRow> checkpoint = new ArrayList<>();
            for (ArticleSync sync : pending) {
                buffer.addAll(sync.docs());
                checkpoint.add(new RagLedgerMapper.LedgerRow(sync.idStr(), sync.ledgerValue()));
                if (buffer.size() >= BATCH) {
                    vectorStore.add(new ArrayList<>(buffer));
                    buffer.clear();
                    ledgerMapper.upsertAll(new ArrayList<>(checkpoint));
                    processed += checkpoint.size();
                    checkpoint.clear();
                    progress.put("processed", String.valueOf(processed));
                    log.info("知识库同步: 续传进度 {}/{} 条", processed, pending.size());
                }
            }
            if (!buffer.isEmpty()) {
                vectorStore.add(buffer);
                ledgerMapper.upsertAll(checkpoint);
                processed += checkpoint.size();
                progress.put("processed", String.valueOf(processed));
            }
            if (pending.isEmpty() && toDelete.isEmpty()) {
                log.info("知识库同步: 无变更，跳过向量化（底账与向量库一致）");
            } else {
                log.info("知识库同步: 新增/变更 {} 条，回收 {} 条", pending.size(), toDelete.size());
            }
        } catch (Exception e) {
            // 降级：导入失败不阻断启动，检索侧会降级为空/用旧索引；已 checkpoint 部分不丢
            log.warn("知识库同步失败（PG/向量库可能不可用），RAG 将降级: {}", e.getMessage());
            return e.getMessage();
        }
        return null;
    }

    /**
     * 确定性文档 ID：PgVectorStore 的 id 列为 uuid 类型（写入时 UUID.fromString），
     * 不接受 "law:article:1001" 字符串键；用 UUID v3（MD5 派生）把语义键确定性映射为 UUID——
     * 同键永远同 UUID，"确定性 ID 精准重导入/精准回收"决策保留
     */
    private static String deterministicUuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** ledger 旧格式（字符串语义键）兼容：已是 UUID 原样返回，否则按同规则派生（删除旧向量时 no-op 安全） */
    private static String toUuidString(String docIdOrKey) {
        try {
            UUID.fromString(docIdOrKey);
            return docIdOrKey;
        } catch (IllegalArgumentException e) {
            return deterministicUuid(docIdOrKey);
        }
    }

    /**
     * 条文 → 向量文档列表
     * <p>
     * 短条文（content ≤ chunkMaxLength）整条一个文档（id = law:article:{id}）；
     * 长条文拆成多个子块文档（id = law:article:{id}#c{i}，metadata chunked=1），
     * 检索侧命中子块后按 articleId 还原整条全文（检索粒度保精度，注入粒度保完整）。
     */
    private List<Document> toDocuments(LawArticleEntity article) {
        Map<String, Object> baseMeta = Map.of(
                "articleId", article.getId(),
                "lawName", article.getLawName(),
                "articleNo", article.getArticleNo(),
                "category", article.getCategory() == null ? "" : article.getCategory(),
                "docType", "law");
        String content = article.getContent() == null ? "" : article.getContent();
        if (content.length() <= ragProperties.getChunkMaxLength()) {
            return List.of(Document.builder()
                    .id(deterministicUuid(DOC_ID_PREFIX + article.getId()))
                    .text(article.toEmbeddingText())
                    .metadata(baseMeta)
                    .build());
        }
        List<String> chunks = splitChunks(content, ragProperties.getChunkMaxLength());
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>(baseMeta);
            meta.put("chunked", "1");
            meta.put("chunkIdx", String.valueOf(i));
            docs.add(Document.builder()
                    .id(deterministicUuid(DOC_ID_PREFIX + article.getId() + "#c" + i))
                    .text("法律名称：" + article.getLawName() + "。条文编号：" + article.getArticleNo()
                            + "。条文内容（第" + (i + 1) + "段）：" + chunks.get(i))
                    .metadata(meta)
                    .build());
        }
        return docs;
    }

    /**
     * 按句拆块（句尾标点 。；！？ 切分，贪心累积到 chunkMaxLength；单句超限强制硬切）。
     * 包级可见 + static，供金标测试直接验证拆块边界。
     */
    static List<String> splitChunks(String content, int maxLen) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : content.split("(?<=[。；！？])")) {
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > maxLen) {
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += maxLen) {
                    chunks.add(sentence.substring(i, Math.min(i + maxLen, sentence.length())));
                }
                continue;
            }
            if (cur.length() + sentence.length() > maxLen && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        return chunks;
    }

    /**
     * 解析 ledger value 中的旧文档 ID 列表（格式 "md5;id1,id2"；旧格式无分号 → 单整条文档）
     */
    private List<String> parseDocIds(String idStr, String ledgerValue) {
        int sep = ledgerValue == null ? -1 : ledgerValue.indexOf(';');
        if (sep < 0) {
            return List.of(toUuidString(DOC_ID_PREFIX + idStr));
        }
        String ids = ledgerValue.substring(sep + 1);
        if (ids.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(ids.split(",")).map(KnowledgeImportService::toUuidString).toList();
    }

    /**
     * MD5 指纹（16 进制字符串）：内容级变更检测，改一个字都会触发重导入；
     * 指纹掺入 embedding 模型名盐——换向量模型 = 全量指纹失配 = 自动全量重建。
     * <p>
     * <b>告诫</b>：改 dimensions/index-type/distance-type 不改变指纹，不会触发重建——
     * 必须手动 DROP TABLE vector_store + DELETE FROM rag_ledger 后重启重同步
     * （详见 application.yml pgvector 配置块注释的四步曲）
     */
    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 是 JDK 必备算法，理论不会走到；兜底用 hashCode 保证流程不中断
            return String.valueOf(text.hashCode());
        }
    }
}
