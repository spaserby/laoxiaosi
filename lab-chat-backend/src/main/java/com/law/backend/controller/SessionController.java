package com.law.backend.controller;

import com.law.backend.service.SessionIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** 会话列表 */
    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", sessionIndexService.list());
    }

    /** 会话历史消息：Spring AI Message 转 {role, content} DTO */
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable("sessionId") String sessionId) {
        List<Map<String, String>> data = chatMemory.get(sessionId).stream()
                .map(this::toDto)
                .toList();
        return Map.of("code", 200, "data", data);
    }

    /** 删除会话：记忆本体 + 索引条目 */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable("sessionId") String sessionId) {
        chatMemory.clear(sessionId);
        sessionIndexService.remove(sessionId);
        return Map.of("code", 200, "message", "ok");
    }

    /** Message → 前端 DTO（role 小写：user/assistant/system） */
    private Map<String, String> toDto(Message message) {
        return Map.of(
                "role", message.getMessageType().name().toLowerCase(),
                "content", message.getText() == null ? "" : message.getText());
    }
}
