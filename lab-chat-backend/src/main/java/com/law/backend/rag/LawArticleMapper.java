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

    /** 指定法名的有效条文（勾选同步的作用域） */
    @Select({"<script>",
            "select * from law_article where deleted = 0 and law_name in",
            "<foreach collection='lawNames' item='n' open='(' separator=',' close=')'>#{n}</foreach>",
            " order by id",
            "</script>"})
    List<LawArticleEntity> findAllActiveByLawNames(@Param("lawNames") List<String> lawNames);

    /** 指定法名的条文 id（勾选同步时"删除回收"的作用域边界） */
    @Select({"<script>",
            "select id from law_article where deleted = 0 and law_name in",
            "<foreach collection='lawNames' item='n' open='(' separator=',' close=')'>#{n}</foreach>",
            "</script>"})
    List<Long> findIdsByLawNames(@Param("lawNames") List<String> lawNames);

    /**
     * 同步清单（管理端勾选界面）：按法名汇总条数与已向量化条数。
     * article_id 在 rag_ledger 中是 VARCHAR，需把 BIGINT 的 id 转文本再关联。
     */
    @Select("select a.law_name as \"lawName\", coalesce(a.category,'') as \"category\","
            + " coalesce(a.doc_type,'') as \"docType\", count(*) as \"cnt\","
            + " count(l.article_id) as \"synced\""
            + " from law_article a left join rag_ledger l on l.article_id = a.id::text"
            + " where a.deleted = 0"
            + " group by a.law_name, a.category, a.doc_type"
            + " order by a.doc_type, a.category, a.law_name")
    List<Map<String, Object>> listSyncScope();

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
