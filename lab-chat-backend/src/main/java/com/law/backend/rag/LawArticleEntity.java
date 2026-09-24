package com.law.backend.rag;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 法律条文实体
 * <p>
 * 表结构与 PG law_article 一致（列名下划线 → 驼峰由 MyBatis 配置自动映射）。
 * <p>
 * <b>定位</b>：PG 是法律知识的"唯一事实源（底账）"，vector_store 只是从它派生的
 * "检索索引"——索引可随时用本表数据重建（换 embedding 模型 / 向量数据丢失时）。
 * <p>
 * 注意：id 由数据侧生成（导入脚本指定 ID 段），应用只读不写。
 */
@Data
public class LawArticleEntity {

    /** 主键 ID */
    private Long id;

    /** 所属法律名称，如《中华人民共和国民法典》 */
    private String lawName;

    /** 条文编号，如第五百七十七条 */
    private String articleNo;

    /** 法律类别：民事/刑事/劳动等 */
    private String category;

    /** 条文标题（可选） */
    private String title;

    /** 版本信息 */
    private String versionInfo;

    /** 章属 */
    private String chapterInfo;

    /** 节属 */
    private String sectionInfo;

    /** 条文内容（知识库检索关键字段） */
    private String content;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

    /**
     * 拼接为自然语言文本用于向量化：
     * 带上法律名称、条文编号等检索维度，让 embedding 同时捕获"内容语义"和"出处语义"。
     */
    public String toEmbeddingText() {
        String base = "法律名称：" + lawName + "。条文编号：" + articleNo + "。条文内容：" + content;
        // 章属语义锚点（"劳务派遣"→第五章第二节），检索结构语义加成
        if (chapterInfo != null && !chapterInfo.isBlank()) {
            base += "。所属章：" + chapterInfo;
        }
        if (sectionInfo != null && !sectionInfo.isBlank()) {
            base += "。所属节：" + sectionInfo;
        }
        return base;
    }
}
