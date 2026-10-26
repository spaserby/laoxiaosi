package com.law.backend.controller;

import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionIndexService;
import com.law.backend.service.SessionOwnershipService;
import com.law.backend.util.Blocking;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 会话管理 Controller（前端工程配套增量：支撑侧栏会话列表/历史回看/删除）
 * <p>
 * 接口清单：
 * <ul>
 *   <li>GET /session/list：会话列表（updatedAt 倒序）</li>
 *   <li>GET /session/{id}/messages：会话历史消息（role/content 结构，供前端回看）</li>
 *   <li>DELETE /session/{id}：删除会话（记忆 + 索引一并清除）</li>
 * </ul>
 * 统一返回 {code, data/message} 轻量结构（demo 不引入主工程的 R 包装类）。
 */
@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionIndexService sessionIndexService;
    private final com.law.backend.memory.ConversationHistoryService historyService;
    private final SessionOwnershipService ownershipService;
    private final com.law.backend.service.ChatFileService chatFileService;

    /** 会话列表：只返回当前调用方的会话（登录用户按 userId；匿名按 X-Guest-Key 凭据） */
    @GetMapping("/list")
    public Mono<Map<String, Object>> list(@AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        // 会话列表读 PG（sessionMapper.findAllByOwner），必须在弹性线程执行
        return Blocking.<Map<String, Object>>call(() -> {
            SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
            if (SessionOwnershipService.ANON.equals(owner.key())) {
                // 无任何凭据：不返回任何历史（旧行为是"全站会话列表"，属越权读取）
                return Map.of("code", 200, "data", List.of());
            }
            return Map.of("code", 200, "data", sessionIndexService.list(owner.key()));
        });
    }

    /** 用户原文历史分页；内部摘要与工具消息永远不进入响应。 */
    @GetMapping("/{sessionId}/messages")
    public Mono<Map<String, Object>> messages(@PathVariable("sessionId") String sessionId,
                                              @AuthenticationPrincipal Long userId, ServerWebExchange exchange,
                                              @RequestParam(value = "beforeMessageId", required = false) Long before,
                                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Blocking.<Map<String, Object>>call(() -> {
            var owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
            ownershipService.claim(sessionId, owner);
            ownershipService.require(sessionId, owner);
            var page = historyService.page(sessionId, owner, before, limit);
            return Map.of("code", 200, "data", page.data(), "hasMore", page.hasMore(), "nextCursor", page.nextCursor());
        });
    }

    /** 删除会话：记忆本体 + 索引条目（非本人会话 → 403） */
    @DeleteMapping("/{sessionId}")
    public Mono<Map<String, Object>> delete(@PathVariable("sessionId") String sessionId,
                                            @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        return Blocking.<Map<String, Object>>call(() -> {
            SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
            ownershipService.claim(sessionId, owner);
            ownershipService.require(sessionId, owner);
            chatFileService.deleteSession(sessionId, owner);
            historyService.delete(sessionId, owner);
            return Map.of("code", 200, "message", "ok");
        });
    }

    /** 越权访问会话 → 403（前端据此提示而非静默失败） */
    @ExceptionHandler(SessionAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleDenied(SessionAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

}
