package com.law.backend.memory;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 早期历史滚动摘要 Mapper
 */
@Mapper
public interface SummaryMapper {

    /** 滚动覆盖：新摘要 = f(旧摘要 + 本批裁掉消息) */
    @Insert("insert into chat_summary(session_id, content) values(#{sessionId}, #{content})"
            + " on conflict (session_id) do update set content = excluded.content, updated_at = current_timestamp")
    int upsert(@Param("sessionId") String sessionId, @Param("content") String content);

    @Select("select content from chat_summary where session_id = #{sessionId}")
    String select(@Param("sessionId") String sessionId);

    @Delete("delete from chat_summary where session_id = #{sessionId}")
    int delete(@Param("sessionId") String sessionId);
}
