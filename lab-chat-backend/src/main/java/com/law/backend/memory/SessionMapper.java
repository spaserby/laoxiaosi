package com.law.backend.memory;

import com.law.backend.service.SessionIndexService;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会话索引 Mapper
 * <p>
 * 与消息同库同事务，"列表在、内容没了"的悬空问题从结构上根除。
 * upsert 语义：首轮建条目（title 取首条消息），后续只刷 updated_at（title 不被覆盖）。
 */
@Mapper
public interface SessionMapper {

    @Insert("insert into chat_session(session_id, title, created_at, updated_at, owner_key)"
            + " values(#{sessionId}, #{title}, #{createdAt}, #{updatedAt}, #{ownerKey})"
            + " on conflict (session_id) do update set updated_at = excluded.updated_at")
    int upsert(@Param("sessionId") String sessionId, @Param("title") String title,
               @Param("createdAt") long createdAt, @Param("updatedAt") long updatedAt,
               @Param("ownerKey") String ownerKey);

    /**
     * 侧栏列表：<b>只返回归属于该 owner 的会话</b>（updatedAt 倒序）。
     * <p>★ 安全约束：此处刻意不提供"全量列表"方法——会话接口一律按归属过滤，
     * 少了全局查询入口就不存在"忘了加 where"的回归路径。
     */
    @Select("select session_id as \"sessionId\", title, created_at as \"createdAt\", updated_at as \"updatedAt\""
            + " from chat_session where owner_key = #{ownerKey} order by updated_at desc")
    List<SessionIndexService.SessionMeta> findAllByOwner(@Param("ownerKey") String ownerKey);

    /** 会话归属键（不存在返回 null）——读写删前的越权判定依据 */
    @Select("select owner_key from chat_session where session_id = #{sessionId}")
    String findOwnerKey(@Param("sessionId") String sessionId);

    /** 匿名转登录时升级归属（仅同一浏览器凭据匹配时由 SessionOwnershipService 调用） */
    @Update("update chat_session set owner_key = #{ownerKey} where session_id = #{sessionId}")
    int setOwnerKey(@Param("sessionId") String sessionId, @Param("ownerKey") String ownerKey);

    /** 删除：条件里带归属，越权删除在 SQL 层就不可能生效 */
    @Delete("delete from chat_session where session_id = #{sessionId} and owner_key = #{ownerKey}")
    int deleteOwned(@Param("sessionId") String sessionId, @Param("ownerKey") String ownerKey);
}
