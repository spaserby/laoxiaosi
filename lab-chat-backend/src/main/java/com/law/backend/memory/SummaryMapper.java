package com.law.backend.memory;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SummaryMapper {
    /** 调用方先锁会话行；不存在的会话不允许创建摘要。 */
    @Insert("insert into chat_summary(session_id,content) select session_id,'' from chat_session "
            + "where session_id=#{sessionId} and not exists(select 1 from chat_summary where session_id=#{sessionId})")
    int ensure(String sessionId);
    @Select("select session_id,content,covered_until_message_id,version,legacy from chat_summary where session_id=#{sessionId}")
    SummaryState state(String sessionId);
    @Select("select s.session_id from chat_summary s join chat_session c on c.session_id=s.session_id "
            + "where s.next_attempt_at<=#{now} and (s.lease_until is null or s.lease_until<#{now}) "
            + "and (select count(*) from chat_message m where m.session_id=s.session_id and m.role='USER' "
            + "and m.status<>'RUNNING' and m.id>s.covered_until_message_id)>#{rounds} "
            + "order by s.next_attempt_at,s.session_id limit #{limit}")
    List<String> pending(@Param("now") LocalDateTime now, @Param("rounds") int rounds, @Param("limit") int limit);
    @Update("update chat_summary set lease_token=#{token},lease_until=#{until} where session_id=#{sessionId} "
            + "and next_attempt_at<=#{now} and (lease_until is null or lease_until<#{now})")
    int claim(@Param("sessionId") String sessionId, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);
    /** 游标、版本、租约同时匹配，已删除或被新任务接管的行不会被复活。 */
    @Update("update chat_summary set content=#{content},covered_until_message_id=#{covered},version=version+1,updated_at=#{now}, "
            + "lease_token=null,lease_until=null,failures=0,next_attempt_at=#{now} where session_id=#{sessionId} "
            + "and version=#{version} and covered_until_message_id=#{previous} and lease_token=#{token} and lease_until>#{now}")
    int commit(@Param("sessionId") String sessionId, @Param("token") String token, @Param("version") long version,
               @Param("previous") long previous, @Param("covered") long covered, @Param("content") String content,
               @Param("now") LocalDateTime now);
    @Select("select failures from chat_summary where session_id=#{sessionId} and lease_token=#{token}")
    Integer failures(@Param("sessionId") String sessionId, @Param("token") String token);
    @Update("update chat_summary set lease_until=#{until} where session_id=#{sessionId} and lease_token=#{token} and lease_until>#{now}")
    int renew(@Param("sessionId") String sessionId, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);
    @Update("update chat_summary set lease_token=null,lease_until=null,failures=failures+1,next_attempt_at=#{retryAt} "
            + "where session_id=#{sessionId} and lease_token=#{token}")
    int retry(@Param("sessionId") String sessionId, @Param("token") String token, @Param("retryAt") LocalDateTime retryAt);
    @Delete("delete from chat_summary where session_id=#{sessionId}")
    int delete(String sessionId);
}
