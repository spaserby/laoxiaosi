package com.law.backend.controller;

import com.law.backend.rag.LawArticleBrowseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 法条知识库 Controller（前端工程配套增量：支撑"法条知识库"页面浏览/搜索/分类/分页）
 * <p>
 * <b>分层纪律</b>：控制器只做参数接收与响应封装，底账读取全部经
 * {@link LawArticleBrowseService}；控制器 import 不出现 Mapper。
 * <p>
 * 数据源是 PG 底账 law_article（唯一事实源），与 RAG 向量检索互不干扰：
 * 本接口是精确浏览（分页 + 关键词模糊 + 分类过滤），向量检索是语义召回，各司其职。
 */
@RestController
@RequestMapping("/law-article")
@RequiredArgsConstructor
public class LawArticleController {

    private final LawArticleBrowseService browseService;

    /**
     * 分页搜索法条
     *
     * @param lawName   法律名称模糊（如 劳动合同法），空串不过滤
     * @param articleNo 条文编号模糊（如 第四十七 或 47），空串不过滤
     * @param keyword   正文关键词模糊，空串不过滤
     * @param category  分类（民事/劳动/刑事...），空串不过滤
     * @param page      页码，从 1 开始
     * @param size      每页条数
     */
    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "") String lawName,
                                    @RequestParam(defaultValue = "") String articleNo,
                                    @RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "") String category,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        LawArticleBrowseService.PageResult r = browseService.page(lawName, articleNo, keyword, category, page, size);
        return Map.of("code", 200, "data", Map.of("total", r.total(), "list", r.list()));
    }

    /**
     * 分类计数（分类 chips 角标）：[{category, count}]
     */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return Map.of("code", 200, "data", browseService.categories());
    }

    /**
     * 单条法条详情（引用卡按需展开：SSE 引用载荷不带正文时，前端据此补正文/章节属）
     */
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam("id") long id) {
        LawArticleBrowseService.ArticleDetail data = browseService.detail(id);
        return data == null ? Map.of("code", 404, "message", "法条不存在")
                : Map.of("code", 200, "data", data);
    }
}
