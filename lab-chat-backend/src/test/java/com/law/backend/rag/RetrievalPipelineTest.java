package com.law.backend.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索管线数学金标：RRF 融合与长条文分块的纯函数验证
 * <p>
 * 两阶段检索/混合检索的"数学部分"抽为 static 纯函数，单测锁死融合排序与分块边界，
 * 网络/向量库相关的集成行为由 e2e 覆盖。
 */
class RetrievalPipelineTest {

    private LawCitation cite(String law, String no, double score) {
        return new LawCitation(1L, law, no, "民事", score, law + no, false);
    }

    @Test
    @DisplayName("RRF 融合：双路命中的条文排最前（score = 1/(60+1) + 1/(60+1)）")
    void rrf_bothPathsWin() {
        List<LawCitation> dense = List.of(cite("甲法", "第一条", 0.9), cite("乙法", "第二条", 0.8));
        List<LawCitation> sparse = List.of(cite("乙法", "第二条", 0.0), cite("丙法", "第三条", 0.0));
        List<LawCitation> fused = KnowledgeRetrievalService.rrfFuse(dense, sparse, 10);
        assertEquals("乙法", fused.get(0).lawName(), "双路命中的条文融合分最高");
        assertEquals("甲法", fused.get(1).lawName(), "稠密路 rank1 次之");
        assertEquals("丙法", fused.get(2).lawName());
    }

    @Test
    @DisplayName("RRF 融合：条文级去重（同法名条号的多 chunk 只保留一个）")
    void rrf_dedupByArticle() {
        List<LawCitation> dense = List.of(cite("甲法", "第一条", 0.9), cite("甲法", "第一条", 0.7));
        List<LawCitation> fused = KnowledgeRetrievalService.rrfFuse(dense, List.of(), 10);
        assertEquals(1, fused.size(), "同键去重");
    }

    @Test
    @DisplayName("RRF 融合：limit 截断生效")
    void rrf_limit() {
        List<LawCitation> dense = List.of(cite("甲", "一", 0.9), cite("乙", "二", 0.8), cite("丙", "三", 0.7));
        assertEquals(2, KnowledgeRetrievalService.rrfFuse(dense, List.of(), 2).size());
    }

    @Test
    @DisplayName("分块：短条文不拆（单块）")
    void chunk_shortSingle() {
        List<String> chunks = KnowledgeImportService.splitChunks("第一句。第二句。", 480);
        assertEquals(1, chunks.size());
        assertEquals("第一句。第二句。", chunks.get(0));
    }

    @Test
    @DisplayName("分块：长条文按句贪心累积，块不超 maxLen")
    void chunk_longSplitBySentence() {
        String sentence = "甲乙丙丁戊己庚辛壬癸。";   // 11 字/句
        String content = sentence.repeat(10);          // 110 字
        List<String> chunks = KnowledgeImportService.splitChunks(content, 30);
        assertTrue(chunks.size() > 1);
        for (String c : chunks) {
            assertTrue(c.length() <= 30, "块长不超阈值: " + c.length());
        }
        // 内容不丢：拼接还原 = 原文
        assertEquals(content, String.join("", chunks));
    }

    @Test
    @DisplayName("分块：单句超限强制硬切且可还原")
    void chunk_singleHugeSentenceHardCut() {
        String huge = "字".repeat(100);   // 无句尾标点的超长单句
        List<String> chunks = KnowledgeImportService.splitChunks(huge, 30);
        assertEquals(4, chunks.size());
        assertEquals(huge, String.join("", chunks));
    }
}
