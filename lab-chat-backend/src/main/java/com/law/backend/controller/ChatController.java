package com.law.backend.controller;

import com.law.backend.auth.AuthService;
import com.law.backend.quota.QuotaService;
import com.law.backend.service.ChatFileService;
import com.law.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 聊天 Controller：SSE 流式问答与停止生成入口
 * <p>
 * SSE 事件类型：
 * <ul>
 *   <li>token：AI 生成的 token 片段</li>
 *   <li>done：生成完成标记，data 为 "[DONE]"</li>
 *   <li>error：生成异常，data 为错误信息</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    /** 会话归属记录（登录态 sessionId → userId，律师门禁分级地基） */
    private final RedissonClient redissonClient;
    /** 配额角色反查（userId → role → tier） */
    private final AuthService authService;
    /** 会话附件（上传解析暂存） */
    private final ChatFileService chatFileService;

    /**
     * 文档上传：校验+解析+暂存 Redis 24h，返回 fileId 列表供 stream 携带
     * <p>WebFlux 栈文件参数必须用反应式 {@link FilePart}（Servlet 的 MultipartFile
     * 在 reactive 运行时无 resolver，报 415 Unsupported Media Type）
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public reactor.core.publisher.Mono<List<ChatFileService.UploadedFile>> uploadFiles(
            @RequestPart("files") List<org.springframework.http.codec.multipart.FilePart> parts) {
        return reactor.core.publisher.Flux.fromIterable(parts)
                .concatMap(part -> org.springframework.core.io.buffer.DataBufferUtils.join(part.content())
                        .map(buf -> {
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                            return new ChatFileService.FilePayload(part.filename(), bytes);
                        }))
                .collectList()
                .map(chatFileService::store);
    }

    /**
     * 流式聊天（Flux SSE）
     *
     * @param sessionId   会话 ID（调用方生成）
     * @param userMessage 用户消息
     * @return SSE 事件流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam("sessionId") String sessionId,
                                                @RequestParam("userMessage") String userMessage,
                                                @RequestParam(required = false) List<String> fileIds,
                                                @AuthenticationPrincipal Long userId,
                                                ServerWebExchange exchange) {
        log.info("收到聊天请求: sessionId={}, message={}", sessionId, userMessage);
        // 配额上下文（登录=u:{userId}+role；游客=ip:{ip} 共享池）
        QuotaService.QuotaCtx quotaCtx = quotaContext(userId, exchange);
        // 登录增强——带有效 token（Authorization 头或 ?token= 参数，JwtAuthFilter 已解析）
        // 时把会话归属到 userId；匿名会话 userId 为 null 不受影响（聊天保持匿名可用）
        if (userId != null) {
            try {
                redissonClient.getBucket("chat:owner:" + sessionId)
                        .set(String.valueOf(userId), java.time.Duration.ofHours(24));
            } catch (Exception e) {
                log.warn("会话归属记录失败（不影响对话）: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        return chatService.chat(sessionId, userMessage, quotaCtx, fileIds);
    }

    /**
     * 配额上下文：登录用户 = u:{userId}（role 反查库）；游客 = ip:{ip}（同 IP 共享游客池）
     */
    private QuotaService.QuotaCtx quotaContext(Long userId, ServerWebExchange exchange) {
        if (userId != null) {
            try {
                String role = authService.me(userId).role();
                return new QuotaService.QuotaCtx(QuotaService.tierOf(role), "u:" + userId);
            } catch (Exception e) {
                log.warn("配额角色反查失败，按游客计: userId={}, error={}", userId, e.getMessage());
            }
        }
        String ip = exchange.getRequest().getRemoteAddress() == null
                ? "unknown" : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        return new QuotaService.QuotaCtx("guest", "ip:" + ip);
    }

    /**
     * 停止生成：取消指定会话正在进行的 LLM 流式调用
     */
    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam("sessionId") String sessionId) {
        chatService.stop(sessionId);
        return Map.of("code", 200, "message", "ok");
    }

    /**
     * 业务校验失败统一 400（附件格式/大小/解析拒绝等）：
     * 无此映射时 WebFlux 把 IllegalArgumentException 默认成 500，前端拿不到可读 message
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage() == null ? "请求错误" : e.getMessage()));
    }
}
