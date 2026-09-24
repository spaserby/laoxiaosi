package com.law.backend.rag;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 法条底账 Mapper
 * <p>
 * 稀疏路检索用 pg_trgm GIN 索引加速 ILIKE（取代 MySQL FULLTEXT ngram），
 * 排序用 similarity() 三元组相似度近似 BM25 的相关性序。
 */
@Mapper
public interface LawArticleMapper {

    /** 全部有效条文（导入管道全量扫描） */
    @Select("select * from law_article where deleted = 0 order by id")
    List<LawArticleEntity> findAllActive();

    /** 主键查（Small-to-Big 还原整条全文） */
    @Select("select * from law_article where id = #{id}")
    LawArticleEntity findById(@Param("id") Long id);

    /** 精确查条：法名去书名号后模糊包含，条号精确匹配 */
    @Select("select * from law_article where deleted = 0"
            + " and law_name like '%' || #{lawName} || '%' and article_no = #{articleNo}"
            + " order by id limit 1")
    LawArticleEntity findByLawNameAndArticleNo(@Param("lawName") String lawName, @Param("articleNo") String articleNo);

    /** 批量主键查（citation 版本/章节属反查，一次 IN 查询） */
    @Select({"<script>",
            "select * from law_article where id in",
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    List<LawArticleEntity> findAllByIds(@Param("ids") List<Long> ids);

    /** 首页事实栏：有效条文总数 */
    @Select("select count(*) from law_article where deleted = #{deleted}")
    long countByDeleted(@Param("deleted") int deleted);

    /** 首页事实栏：有效法律部数（distinct 法名） */
    @Select("select count(distinct law_name) from law_article where deleted = 0")
    long countDistinctLawNames();

    /** 知识库三字段联合检索 */
    @Select({"<script>",
            "select * from law_article where deleted = 0",
            "<if test='category != null'> and category = #{category}</if>",
            "<if test='lawName != null'> and law_name ilike '%' || #{lawName} || '%'</if>",
            "<if test='articleNo != null'> and article_no ilike '%' || #{articleNo} || '%'</if>",
            "<if test='keyword != null'> and content ilike '%' || #{keyword} || '%'</if>",
            " order by id limit #{limit} offset #{offset}",
            "</script>"})
    List<LawArticleEntity> search(@Param("lawName") String lawName,
                                  @Param("articleNo") String articleNo,
                                  @Param("keyword") String keyword,
                                  @Param("category") String category,
                                  @Param("offset") int offset, @Param("limit") int limit);

    /** 联合检索计数（与 search 同条件） */
    @Select({"<script>",
            "select count(*) from law_article where deleted = 0",
            "<if test='category != null'> and category = #{category}</if>",
            "<if test='lawName != null'> and law_name ilike '%' || #{lawName} || '%'</if>",
            "<if test='articleNo != null'> and article_no ilike '%' || #{articleNo} || '%'</if>",
            "<if test='keyword != null'> and content ilike '%' || #{keyword} || '%'</if>",
            "</script>"})
    long countSearch(@Param("lawName") String lawName,
                     @Param("articleNo") String articleNo,
                     @Param("keyword") String keyword,
                     @Param("category") String category);

    /** 分类计数（知识库页 chips 角标）：行 = {category, cnt} */
    @Select("select category, count(*) as cnt from law_article where deleted = 0 group by category")
    List<Map<String, Object>> countByCategory();

    /**
     * 稀疏路关键词检索：trgm GIN 加速 ILIKE 候选，
     * similarity 相似度排序补"第四十七条"这类离散编号的召回短板
     */
    @Select("select * from law_article where deleted = 0"
            + " and (content ilike '%' || #{kw} || '%' or law_name ilike '%' || #{kw} || '%'"
            + "      or article_no ilike '%' || #{kw} || '%')"
            + " order by similarity(content, #{kw}) desc limit #{limit}")
    List<LawArticleEntity> keywordSearch(@Param("kw") String kw, @Param("limit") int limit);
}
