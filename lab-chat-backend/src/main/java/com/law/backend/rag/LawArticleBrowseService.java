package com.law.backend.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 法条浏览服务
 * <p>
 * <b>分层纪律</b>：依赖方向 controller → service → mapper 单向，控制器 import 里
 * 不允许出现 Mapper；本服务承载知识库页面的分页搜索与分类计数（读模型）。
 * <p>
 * <b>领域轴</b>：law_article 属 rag 域，故本服务与 Mapper 同包内聚。
 */
@Service
@RequiredArgsConstructor
public class LawArticleBrowseService {

    private final LawArticleMapper mapper;

    /** 浏览 DTO（只暴露展示字段，null 归一空串） */
    public record ArticleDto(String id, String lawName, String articleNo,
                             String category, String title, String content) {
    }

    /** 分页结果 */
    public record PageResult(long total, List<ArticleDto> list) {
    }

    /** 分类计数行（知识库页 chips 角标） */
    public record CategoryCount(String category, long count) {
    }

    /**
     * 三字段联合检索分页
     */
    public PageResult page(String lawName, String articleNo, String keyword, String category,
                           int page, int size) {
        int limit = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page - 1, 0) * limit;
        String ln = blankToNull(lawName);
        String an = blankToNull(articleNo);
        String kw = blankToNull(keyword);
        String cat = blankToNull(category);
        long total = mapper.countSearch(ln, an, kw, cat);
        List<ArticleDto> list = mapper.search(ln, an, kw, cat, offset, limit).stream()
                .map(this::toDto).toList();
        return new PageResult(total, list);
    }

    /**
     * 单条详情 DTO（引用卡"按需展开"用）：比列表 DTO 多出章/节属与版本、位阶
     */
    public record ArticleDetail(String id, String lawName, String articleNo, String category,
                                String docType, String versionInfo, String chapterInfo,
                                String sectionInfo, String content) {
    }

    /**
     * 按 id 取单条法条（不存在或已逻辑删除返回 null）。
     * <p>引用卡此前依赖 SSE 引用载荷里带正文，载荷不带正文时展开按钮就消失了；
     * 改为前端按需拉取后，正文是否在载荷里都不影响展开。
     */
    public ArticleDetail detail(long id) {
        LawArticleEntity a = mapper.findById(id);
        if (a == null || (a.getDeleted() != null && a.getDeleted() == 1)) {
            return null;
        }
        return new ArticleDetail(String.valueOf(a.getId()), a.getLawName(), a.getArticleNo(),
                a.getCategory() == null ? "" : a.getCategory(),
                a.getDocType() == null ? "" : a.getDocType(),
                a.getVersionInfo() == null ? "" : a.getVersionInfo(),
                a.getChapterInfo() == null ? "" : a.getChapterInfo(),
                a.getSectionInfo() == null ? "" : a.getSectionInfo(),
                a.getContent() == null ? "" : a.getContent());
    }

    /**
     * 分类计数（category 为 null 的行归"未分类"）
     */
    public List<CategoryCount> categories() {
        return mapper.countByCategory().stream()
                .map(row -> new CategoryCount(
                        row.get("category") == null ? "未分类" : String.valueOf(row.get("category")),
                        ((Number) row.get("cnt")).longValue()))
                .toList();
    }

    private ArticleDto toDto(LawArticleEntity a) {
        return new ArticleDto(String.valueOf(a.getId()), n(a.getLawName()), n(a.getArticleNo()),
                n(a.getCategory()), n(a.getTitle()), n(a.getContent()));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String n(String s) {
        return s == null ? "" : s;
    }
}
