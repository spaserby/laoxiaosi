package com.law.backend.controller;

import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionIndexService;
import com.law.backend.service.SessionOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

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
    private final ChatMemory chatMemory;
    private final SessionOwnershipService ownershipService;

    /** 会话列表：只返回当前调用方的会话（登录用户按 userId；匿名按 X-Guest-Key 凭据） */
    @GetMapping("/list")
    public Map<String, Object> list(@AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
        if (SessionOwnershipService.ANON.equals(owner.key())) {
            // 无任何凭据：不返回任何历史（旧行为是"全站会话列表"，属越权读取）
            return Map.of("code", 200, "data", List.of());
        }
        return Map.of("code", 200, "data", sessionIndexService.list(owner.key()));
    }

    /** 会话历史消息：Spring AI Message 转 {role, content} DTO（非本人会话 → 403） */
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable("sessionId") String sessionId,
                                        @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        ownershipService.require(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId));
        List<Map<String, String>> data = chatMemory.get(sessionId).stream()
                .map(this::toDto)
                .toList();
        return Map.of("code", 200, "data", data);
    }

    /** 删除会话：记忆本体 + 索引条目（非本人会话 → 403） */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable("sessionId") String sessionId,
                                      @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
        ownershipService.require(sessionId, owner);
        chatMemory.clear(sessionId);
        sessionIndexService.remove(sessionId, owner.key());
        return Map.of("code", 200, "message", "ok");
    }

    /** 越权访问会话 → 403（前端据此提示而非静默失败） */
    @ExceptionHandler(SessionAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleDenied(SessionAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    /** Message → 前端 DTO（role 小写：user/assistant/system） */
    private Map<String, String> toDto(Message message) {
        return Map.of(
                "role", message.getMessageType().name().toLowerCase(),
                "content", message.getText() == null ? "" : message.getText());
    }
}
