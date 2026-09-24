package com.law.backend.memory;

import com.law.backend.service.SessionIndexService;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话索引 Mapper
 * <p>
 * 与消息同库同事务，"列表在、内容没了"的悬空问题从结构上根除。
 * upsert 语义：首轮建条目（title 取首条消息），后续只刷 updated_at（title 不被覆盖）。
 */
@Mapper
public interface SessionMapper {

    @Insert("insert into chat_session(session_id, title, created_at, updated_at)"
            + " values(#{sessionId}, #{title}, #{createdAt}, #{updatedAt})"
            + " on conflict (session_id) do update set updated_at = excluded.updated_at")
    int upsert(@Param("sessionId") String sessionId, @Param("title") String title,
               @Param("createdAt") long createdAt, @Param("updatedAt") long updatedAt);

    /** 侧栏列表：updatedAt 倒序（列别名对齐 SessionIndexService.SessionMeta） */
    @Select("select session_id as \"sessionId\", title, created_at as \"createdAt\", updated_at as \"updatedAt\""
            + " from chat_session order by updated_at desc")
    List<SessionIndexService.SessionMeta> findAll();

    @Delete("delete from chat_session where session_id = #{sessionId}")
    int delete(@Param("sessionId") String sessionId);
}
