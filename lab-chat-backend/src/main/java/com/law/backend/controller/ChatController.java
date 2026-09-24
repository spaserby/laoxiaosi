package com.law.backend.controller;

import com.law.backend.auth.AuthService;
import com.law.backend.quota.QuotaService;
import com.law.backend.service.ChatFileService;
import com.law.backend.service.ChatService;
import com.law.backend.service.SessionOwnershipService;
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
import org.springframework.web.bind.annotation.RequestBody;
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
    /** 会话归属校验（登录按 userId，匿名按 X-Guest-Key） */
    private final com.law.backend.service.SessionOwnershipService ownershipService;

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
        return streamInternal(sessionId, userMessage, fileIds, userId, exchange);
    }

    /** POST 流式聊天请求体（会话 ID + 问题 + 附件） */
    public record StreamRequest(String sessionId, String userMessage, List<String> fileIds) {
    }

    /**
     * 流式聊天（POST 版，前端默认走这条）。
     * <p>★ 为什么用 POST：GET 只能把参数拼在 URL 上，而 URL 会进 nginx/代理访问日志——
     * token 与咨询原文都曾因此落盘。POST 把两者放进请求体，日志里只剩路径。
     */
    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamPost(@RequestBody StreamRequest request,
                                                     @AuthenticationPrincipal Long userId,
                                                     ServerWebExchange exchange) {
        return streamInternal(request.sessionId(), request.userMessage(), request.fileIds(),
                userId, exchange);
    }

    private Flux<ServerSentEvent<String>> streamInternal(String sessionId, String userMessage,
                                                         List<String> fileIds, Long userId,
                                                         ServerWebExchange exchange) {
        // ★ 日志不含问题正文：法律咨询内容属敏感个人信息，只记长度等元数据（P0 安全整改）
        log.info("收到聊天请求: sessionId={}, 问题长度={}, 附件数={}", sessionId,
                userMessage == null ? 0 : userMessage.length(), fileIds == null ? 0 : fileIds.size());
        // 配额上下文（登录=u:{userId}+role；游客=ip:{ip} 共享池）
        QuotaService.QuotaCtx quotaCtx = quotaContext(userId, exchange);
        // 会话归属：登录按 userId，匿名按 X-Guest-Key 凭据；他人会话直接 403（不再有"无主会话"）
        SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
        ownershipService.claim(sessionId, owner);
        return chatService.chat(sessionId, userMessage, quotaCtx, fileIds, owner.key());
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
        String ip = com.law.backend.util.ClientIp.of(exchange);
        return new QuotaService.QuotaCtx("guest", "ip:" + ip);
    }

    /**
     * 停止生成：取消指定会话正在进行的 LLM 流式调用
     * <p>★ 归属校验：停止是跨实例的 Redis 信号，若不校验，任何人可用他人 sessionId 打断别人的回答。
     */
    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam("sessionId") String sessionId,
                                    @AuthenticationPrincipal Long userId,
                                    ServerWebExchange exchange) {
        ownershipService.require(sessionId,
                ownershipService.resolve(exchange.getRequest().getHeaders(), userId));
        chatService.stop(sessionId);
        return Map.of("code", 200, "message", "ok");
    }

    /** 越权访问会话 → 403（P0 安全整改） */
    @ExceptionHandler(com.law.backend.service.SessionAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleAccessDenied(
            com.law.backend.service.SessionAccessDeniedException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(Map.of("message", e.getMessage()));
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
