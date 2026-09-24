package com.law.backend.rag;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 向量同步指纹底账 Mapper
 * <p>
 * 指纹落 PG 后获得两件事：多实例共享（不再各实例一份 ledger 互相打架）+
 * 持久化（Redis  flush 不再导致全量重嵌入）。value 格式沿用 "md5;docId1,docId2"。
 */
@Mapper
public interface RagLedgerMapper {

    @Select("select article_id as \"articleId\", ledger_value as \"ledgerValue\" from rag_ledger")
    List<LedgerRow> findAll();

    /** 批量 upsert（分批续传的 checkpoint 写入口） */
    @Insert({"<script>",
            "insert into rag_ledger(article_id, ledger_value) values",
            "<foreach collection='entries' item='e' separator=','>(#{e.articleId}, #{e.ledgerValue})</foreach>",
            "on conflict (article_id) do update set ledger_value = excluded.ledger_value",
            "</script>"})
    int upsertAll(@Param("entries") List<LedgerRow> entries);

    @Delete({"<script>",
            "delete from rag_ledger where article_id in",
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    int deleteAll(@Param("ids") List<String> ids);

    /** 指纹行（articleId → "md5;docIds"） */
    record LedgerRow(String articleId, String ledgerValue) {
    }
}
