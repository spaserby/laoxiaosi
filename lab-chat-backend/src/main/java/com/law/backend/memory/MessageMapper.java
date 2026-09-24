package com.law.backend.memory;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话消息 Mapper
 * <p>
 * id 自增即时序（order by id = 对话顺序）；窗口裁剪 = 删除最旧的 N 行。
 */
@Mapper
public interface MessageMapper {

    @Insert("insert into chat_message(session_id, role, content) values(#{sessionId}, #{role}, #{content})")
    int insert(@Param("sessionId") String sessionId, @Param("role") String role, @Param("content") String content);

    /** 按对话顺序读全部消息（列别名对齐 MessageRecord 字段） */
    @Select("select role as type, content from chat_message where session_id = #{sessionId} order by id")
    List<MessageRecord> selectBySession(@Param("sessionId") String sessionId);

    /** 读最旧 n 条（窗口裁剪前取出供摘要） */
    @Select("select role as type, content from chat_message where session_id = #{sessionId} order by id limit #{n}")
    List<MessageRecord> selectOldest(@Param("sessionId") String sessionId, @Param("n") int n);

    @Select("select count(*) from chat_message where session_id = #{sessionId}")
    long countBySession(@Param("sessionId") String sessionId);

    /** 会话索引/侧栏判活：会话是否有消息 */
    @Select("select exists(select 1 from chat_message where session_id = #{sessionId})")
    boolean existsBySession(@Param("sessionId") String sessionId);

    @Delete("delete from chat_message where id in"
            + " (select id from chat_message where session_id = #{sessionId} order by id limit #{n})")
    int deleteOldest(@Param("sessionId") String sessionId, @Param("n") int n);

    @Delete("delete from chat_message where session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);
}
