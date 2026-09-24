package com.law.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话索引服务（前端工程配套增量：支撑侧栏会话列表）
 * <p>
 * <b>Redis Hash {@code chat:session:index} → PG 表 chat_session。
 * 索引与消息同库后，"悬空剪枝"逻辑整体删除——列表与内容生命周期天然一致，
 * 且历史会话不再因 TTL 消失（持久化回看）。
 * <p>
 * <b>降级设计</b>不变：索引读写失败仅告警不阻断对话主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionIndexService {

    /** 标题最大长度（首条用户消息截断） */
    private static final int TITLE_MAX = 20;

    private final com.law.backend.memory.SessionMapper sessionMapper;

    /** 会话元数据（与 chat_session 行一一对应） */
    public record SessionMeta(String sessionId, String title, long createdAt, long updatedAt) {
    }

    /**
     * 每轮对话调用：首轮创建索引（title = 首条用户消息前 20 字），后续轮次仅刷新 updatedAt
     * （upsert 的 on conflict 只更新 updated_at，title 不被覆盖）
     */
    public void touch(String sessionId, String firstMessage, String ownerKey) {
        try {
            long now = System.currentTimeMillis();
            String title = (firstMessage == null || firstMessage.isBlank())
                    ? "新对话"
                    : (firstMessage.length() > TITLE_MAX ? firstMessage.substring(0, TITLE_MAX) : firstMessage);
            sessionMapper.upsert(sessionId, title, now, now, ownerKey);
        } catch (Exception e) {
            log.warn("会话索引写入失败（不影响对话）: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 会话列表：按 updatedAt 倒序（最近对话在前）
     */
    public List<SessionMeta> list(String ownerKey) {
        try {
            return sessionMapper.findAllByOwner(ownerKey);
        } catch (Exception e) {
            log.warn("会话索引读取失败，返回空列表: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除会话索引条目（消息本体由 ChatMemory.clear 删除）
     */
    public void remove(String sessionId, String ownerKey) {
        try {
            sessionMapper.deleteOwned(sessionId, ownerKey);
        } catch (Exception e) {
            log.warn("会话索引删除失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
