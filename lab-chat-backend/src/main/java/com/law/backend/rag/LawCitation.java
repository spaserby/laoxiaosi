package com.law.backend.rag;

/**
 * 结构化法条引用
 * <p>
 * <b>为什么需要它</b>：早期 {@code retrieve()} 返回 {@code List<String>}，
 * Document metadata 里的 articleId/lawName/articleNo/score 全部丢弃，前端只能靠正则
 * 从模型输出里猜引用——"引用卡展示的是模型说了什么，不是检索命中了什么"，
 * 模型编造的条文号会被 UI 包装成看起来权威的 § 卡片（幻觉盖权威章）。
 * <p>
 * 本 record 让引用以结构化形态贯穿：检索命中 → System Prompt 编号注入 →
 * SSE citation 事件推前端 → AnswerReviewer 真伪校验，全链路同一份数据。
 *
 * @param articleId  底账主键（law_article.id）
 * @param lawName    法律名称，如《中华人民共和国劳动合同法》
 * @param articleNo  条文编号，如第四十七条
 * @param category   分类（民事/劳动/刑事...）
 * @param score      向量相似度（COSINE，越高越相关；BM25 路为 0）
 * @param text       条文文本（chunk 命中时为子块文本，Small-to-Big 还原后为整条）
 * @param chunked    是否长条文子块命中（true 时需按 articleId 还原整条全文）
 */
public record LawCitation(Long articleId, String lawName, String articleNo,
                          String category, double score, String text, boolean chunked) {

    /** 引用唯一键：法名 + 条号（审校真伪校验与去重用） */
    public String key() {
        return lawName + "|" + articleNo;
    }
}
