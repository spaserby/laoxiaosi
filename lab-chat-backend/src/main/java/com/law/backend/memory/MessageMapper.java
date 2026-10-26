package com.law.backend.memory;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 原文归档。id 是分页与摘要游标，禁止因上下文窗口大小删除消息。 */
@Mapper
public interface MessageMapper {
    String COLUMNS = "id, role, content, created_at, turn_id, status";
    @Select("select owner_key from chat_session where session_id=#{sessionId} for update")
    String lockSession(String sessionId);

    @Insert("insert into chat_message(session_id, role, content) values(#{sessionId}, #{role}, #{content})")
    int insert(@Param("sessionId") String sessionId, @Param("role") String role, @Param("content") String content);

    @Insert("insert into chat_message(session_id,role,content,turn_id,status) values(#{sessionId},#{role},#{content},#{turnId},#{status})")
    int insertTurn(@Param("sessionId") String sessionId, @Param("role") String role, @Param("content") String content,
                   @Param("turnId") String turnId, @Param("status") String status);

    @Select("select exists(select 1 from chat_message where session_id=#{sessionId} and status='RUNNING')")
    boolean hasRunning(String sessionId);

    @Update("update chat_message set status=#{status} where session_id=#{sessionId} and turn_id=#{turnId} and role='USER' and status='RUNNING'")
    int finish(@Param("sessionId") String sessionId, @Param("turnId") String turnId, @Param("status") String status);

    @Update("update chat_message set status='INTERRUPTED' where status='RUNNING' and created_at < #{before}")
    int interruptStale(java.time.LocalDateTime before);

    /** SQL 本身包含归属及会话边界，游标不构成授权。多取一行判断 hasMore。 */
    @Select("<script>select m.id,m.role,m.content,m.created_at,m.turn_id,m.status from chat_message m "
            + "join chat_session s on s.session_id=m.session_id where m.session_id=#{sessionId} and s.owner_key=#{ownerKey} "
            + "and m.role in ('USER','ASSISTANT') <if test='before != null'>and m.id &lt; #{before}</if> "
            + "order by m.id desc limit #{limit}</script>")
    List<StoredMessage> history(@Param("sessionId") String sessionId, @Param("ownerKey") String ownerKey,
                                @Param("before") Long before, @Param("limit") int limit);

    /** 由 USER 边界选择整个轮次，排除运行中的轮次和孤立助手消息。 */
    @Select("select " + COLUMNS + " from chat_message where session_id=#{sessionId} and status<>'RUNNING' "
            + "and role in ('USER','ASSISTANT') and id>#{covered} and id >= "
            + "(select min(id) from (select id from chat_message where session_id=#{sessionId} and role='USER' "
            + "and status<>'RUNNING' and id>#{covered} order by id desc limit #{rounds}) recent) order by id")
    List<StoredMessage> recentTurns(@Param("sessionId") String sessionId, @Param("rounds") int rounds, @Param("covered") long covered);

    @Select("select exists(select 1 from chat_message where session_id=#{sessionId} and role='USER' and status<>'RUNNING' and id>#{covered} and id<#{first})")
    boolean hasUncoveredBefore(@Param("sessionId") String sessionId, @Param("covered") long covered, @Param("first") long first);

    @Select("select id from chat_message where session_id=#{sessionId} and role='USER' and status<>'RUNNING' order by id desc limit 1 offset #{offset}")
    Long summaryBoundary(@Param("sessionId") String sessionId, @Param("offset") int offset);

    @Select("select id from chat_message where session_id=#{sessionId} and role='USER' and status<>'RUNNING' "
            + "and id>#{covered} and id<#{boundary} order by id limit #{limit}")
    List<Long> summaryTurns(@Param("sessionId") String sessionId, @Param("covered") long covered,
                            @Param("boundary") long boundary, @Param("limit") int limit);

    @Select("select " + COLUMNS + " from chat_message where session_id=#{sessionId} and id>=#{start} "
            + "and id < (select coalesce(min(id),9223372036854775807) from chat_message where session_id=#{sessionId} and role='USER' and id>#{start}) "
            + "and role in ('USER','ASSISTANT') and status<>'RUNNING' order by id")
    List<StoredMessage> turn(@Param("sessionId") String sessionId, @Param("start") long start);

    @Select("select role as type, content from chat_message where session_id=#{sessionId} order by id")
    List<MessageRecord> selectBySession(String sessionId);
    @Select("select count(*) from chat_message where session_id=#{sessionId}")
    long countBySession(String sessionId);
    @Select("select exists(select 1 from chat_message where session_id=#{sessionId})")
    boolean existsBySession(String sessionId);
    @Delete("delete from chat_message where session_id=#{sessionId}")
    int deleteBySession(String sessionId);
}
