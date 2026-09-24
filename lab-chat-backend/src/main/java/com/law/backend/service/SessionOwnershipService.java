package com.law.backend.service;

import com.law.backend.memory.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 会话归属（P0 安全整改）：会话必须有主人，列表/读取/删除/停止生成一律按主人过滤。
 * <p>
 * <b>owner_key 约定</b>：
 * <ul>
 *   <li>登录用户：{@code u:{userId}}</li>
 *   <li>匿名访客：{@code g:{guestKey}}——guestKey 是前端 localStorage 里的随机串，
 *       经请求头 {@code X-Guest-Key} 带来（匿名也要有独立访问凭据，否则等于无主可读）</li>
 *   <li>无任何凭据的调用方（非浏览器客户端）：{@link #ANON}，其会话不进任何列表、不可读</li>
 * </ul>
 * <b>冲突语义</b>：会话已属于他人 → 抛 {@link SessionAccessDeniedException}（403），
 * 不做静默接管；唯一例外是同一浏览器匿名转登录（{@code g:同key → u:id}）允许升级归属。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionOwnershipService {

    /** 匿名凭据格式（随机串字符集 + 长度约束，防注入与超长键） */
    private static final Pattern GUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** 无凭据调用方的归属键（永不对外可见） */
    public static final String ANON = "anon";

    private final SessionMapper sessionMapper;

    /** 请求方身份：key = 归属键；guestKey = 原始匿名凭据（供升级判定，可能为 null） */
    public record Owner(String key, String guestKey) {
    }

    /** 从请求头与登录态解析归属：登录优先，其次匿名凭据，都没有则 ANON */
    public Owner resolve(HttpHeaders headers, Long userId) {
        String guestKey = guestKey(headers);
        if (userId != null) {
            return new Owner("u:" + userId, guestKey);
        }
        return new Owner(guestKey == null ? ANON : "g:" + guestKey, guestKey);
    }

    private String guestKey(HttpHeaders headers) {
        String g = headers.getFirst("X-Guest-Key");
        return (g != null && GUEST_KEY.matcher(g).matches()) ? g : null;
    }

    /**
     * 聊天入口调用：新会话放行（归属在 touch 时落库）；本人会话放行；
     * 同一浏览器的匿名转登录允许升级；其余越权拒绝。
     */
    public void claim(String sessionId, Owner owner) {
        String cur = sessionMapper.findOwnerKey(sessionId);
        if (cur == null || cur.equals(owner.key())) {
            return;
        }
        if (owner.guestKey() != null && cur.equals("g:" + owner.guestKey()) && owner.key().startsWith("u:")) {
            sessionMapper.setOwnerKey(sessionId, owner.key());
            log.info("会话归属由匿名升级为登录: sessionId={}", sessionId);
            return;
        }
        log.warn("会话归属冲突已拒绝: sessionId={}, requester={}", sessionId, owner.key());
        throw new SessionAccessDeniedException();
    }

    /** 读/删/停止生成入口调用：必须是本会话主人；无凭据方（ANON）一律不可读 */
    public void require(String sessionId, Owner owner) {
        if (ANON.equals(owner.key())) {
            throw new SessionAccessDeniedException();
        }
        String cur = sessionMapper.findOwnerKey(sessionId);
        if (cur == null || !cur.equals(owner.key())) {
            log.warn("会话越权访问已拒绝: sessionId={}, requester={}, owner={}", sessionId, owner.key(), cur);
            throw new SessionAccessDeniedException();
        }
    }
}
