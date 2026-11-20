package com.law.backend.controller;

import com.law.backend.auth.AuthService;
import com.law.backend.attachment.NativeAttachmentGateway;
import com.law.backend.quota.QuotaService;
import com.law.backend.service.ChatFileService;
import com.law.backend.service.ChatService;
import com.law.backend.service.SessionOwnershipService;
import com.law.backend.util.Blocking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
    private final NativeAttachmentGateway nativeAttachments;

    /**
     * 文档流式写入私有目录，绑定会话并异步解析；返回 QUEUED 元数据供轮询。
     * <p>WebFlux 栈文件参数必须用反应式 {@link FilePart}（Servlet 的 MultipartFile
     * 在 reactive 运行时无 resolver，报 415 Unsupported Media Type）
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public reactor.core.publisher.Mono<List<ChatFileService.UploadedFile>> uploadFiles(
            @RequestPart("files") List<org.springframework.http.codec.multipart.FilePart> parts,
            @RequestParam String sessionId, @RequestParam(defaultValue = "false") boolean deferProcessing,
            @RequestParam(required = false) String attachmentPolicyVersion,
            @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        var policy = attachmentPolicyVersion == null ? null : nativeAttachments.confirmPolicy(attachmentPolicyVersion);
        int maxFiles = policy == null ? chatFileService.maxFilesPerTurn() : policy.maxFiles();
        if (parts.isEmpty() || parts.size() > maxFiles) return Mono.error(new IllegalArgumentException("一次上传 1 至 " + maxFiles + " 个附件"));
        var owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
        var uploaded = new java.util.concurrent.CopyOnWriteArrayList<ChatFileService.UploadedFile>();
        var remaining = new AtomicLong(policy == null ? Long.MAX_VALUE : policy.maxTotalBytes());
        boolean deferred = deferProcessing || !nativeAttachments.legacyModesEnabled();
        return Flux.fromIterable(parts).concatMap(part -> {
                    if (policy != null) {
                        long limit = Math.min(remaining.get(), policy.fileLimit(ChatFileService.fileExtension(part.filename())));
                        return chatFileService.upload(sessionId, owner, part, true, limit)
                                .doOnNext(file -> remaining.addAndGet(-file.sizeBytes()));
                    }
                    return deferred ? chatFileService.upload(sessionId, owner, part, true) : chatFileService.upload(sessionId, owner, part);
                })
                .doOnNext(uploaded::add).collectList()
                .onErrorResume(error -> Blocking.run(() -> uploaded.forEach(file -> chatFileService.delete(sessionId, owner, file.fileId())))
                        .onErrorComplete().then(Mono.error(error)))
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) Schedulers.boundedElastic().schedule(() ->
                            uploaded.forEach(file -> chatFileService.delete(sessionId, owner, file.fileId())));
                });
    }

    @GetMapping("/files/capabilities")
    public List<NativeAttachmentGateway.Capability> fileCapabilities() { return nativeAttachments.capabilities(); }

    @GetMapping("/files/policy")
    public ResponseEntity<NativeAttachmentGateway.Policy> attachmentPolicy() {
        return nativeAttachments.currentPolicy()
                .map(policy -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policy))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping("/files/{fileId}/original")
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadOriginal(
            @org.springframework.web.bind.annotation.PathVariable String fileId, @RequestParam String sessionId,
            @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        return Blocking.call(() -> chatFileService.original(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId), fileId))
                .map(original -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(original.metadata().sizeBytes())
                        .header("Cache-Control", "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Disposition", ContentDisposition.attachment()
                                .filename(original.metadata().name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                        .body(DataBufferUtils.read(original.path(), DefaultDataBufferFactory.sharedInstance, 64 * 1024)
                                .subscribeOn(Schedulers.boundedElastic())));
    }

    @GetMapping("/files")
    public Mono<List<ChatFileService.UploadedFile>> files(@RequestParam String sessionId,
            @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        return Blocking.call(() -> chatFileService.list(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId)));
    }

    @PostMapping("/files/{fileId}/retry")
    public Mono<ChatFileService.UploadedFile> retryFile(@org.springframework.web.bind.annotation.PathVariable String fileId,
            @RequestParam String sessionId, @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        if (!nativeAttachments.legacyModesEnabled()) return Mono.error(new IllegalArgumentException("附件解析重试暂不可用"));
        return Blocking.call(() -> chatFileService.retry(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId), fileId));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/files/{fileId}")
    public Mono<Map<String, Boolean>> deleteFile(@org.springframework.web.bind.annotation.PathVariable String fileId,
            @RequestParam String sessionId, @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        return Blocking.call(() -> { chatFileService.delete(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId), fileId); return Map.of("deleted", true); });
    }

    @GetMapping("/files/{fileId}/source")
    public Mono<Map<String, String>> fileSource(@org.springframework.web.bind.annotation.PathVariable String fileId,
            @RequestParam String sessionId, @RequestParam int parentId, @AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        return Blocking.call(() -> Map.of("text", chatFileService.source(sessionId, ownershipService.resolve(exchange.getRequest().getHeaders(), userId), fileId, parentId)));
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
        return streamInternal(sessionId, userMessage, fileIds, "qa", null, userId, exchange);
    }

    /** POST 流式聊天请求体（会话 ID + 问题 + 附件） */
    public record StreamRequest(String sessionId, String userMessage, List<String> fileIds, String mode,
                                String nativeTarget, String attachmentPolicyVersion) {
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
        String revision = request.attachmentPolicyVersion() != null ? request.attachmentPolicyVersion() : request.nativeTarget();
        return streamInternal(request.sessionId(), request.userMessage(), request.fileIds(), request.mode(), revision,
                userId, exchange);
    }

    private Flux<ServerSentEvent<String>> streamInternal(String sessionId, String userMessage,
                                                         List<String> fileIds, String mode, String attachmentPolicyVersion, Long userId,
                                                         ServerWebExchange exchange) {
        // ★ 日志不含问题正文：法律咨询内容属敏感个人信息，只记长度等元数据（P0 安全整改）
        log.info("收到聊天请求: sessionId={}, 问题长度={}, 附件数={}", sessionId,
                userMessage == null ? 0 : userMessage.length(), fileIds == null ? 0 : fileIds.size());
        // ★ 返回 Flux 不代表方法体异步：配额反查（userMapper）与会话归属 claim 都是阻塞 IO，
        //   留在方法体里会在构造 Flux 之前就占住 Netty event loop。改为 defer（延迟到订阅时执行）
        //   + subscribeOn（整段前置逻辑挪到弹性线程）；token 仍由 LLM 网络回调驱动，不长期占线程
        return Flux.defer(() -> {
            if (userMessage == null || userMessage.isBlank()) throw new IllegalArgumentException("问题不能为空");
            if (mode != null && !List.of("qa", "review", "native").contains(mode)) throw new IllegalArgumentException("附件处理模式无效");
            boolean hasAttachments = fileIds != null && !fileIds.isEmpty();
            boolean legacy = nativeAttachments.legacyModesEnabled() && attachmentPolicyVersion == null
                    && ("qa".equals(mode) || "review".equals(mode));
            if ("review".equals(mode) && !legacy) throw new IllegalArgumentException("逐章审查暂未开放，请更新页面后发送附件");
            if ("native".equals(mode) && !hasAttachments) throw new IllegalArgumentException("请添加要发送的附件");
            String target = hasAttachments && !legacy ? nativeAttachments.confirmPolicy(attachmentPolicyVersion).revision() : null;
            // 配额上下文（登录=u:{userId}+role；游客=ip:{ip} 共享池）
            QuotaService.QuotaCtx quotaCtx = quotaContext(userId, exchange);
            // 会话归属：登录按 userId，匿名按 X-Guest-Key 凭据；他人会话直接 403（不再有"无主会话"）
            SessionOwnershipService.Owner owner = ownershipService.resolve(exchange.getRequest().getHeaders(), userId);
            ownershipService.claim(sessionId, owner);
            return chatService.chat(sessionId, userMessage, quotaCtx, fileIds == null && !legacy ? List.of() : fileIds,
                    owner, legacy && "review".equals(mode), target);
        }).subscribeOn(Schedulers.boundedElastic());
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
                log.warn("配额角色反查失败，按游客计: userId={}, error={}", userId, e.getClass().getSimpleName());
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
    public Mono<Map<String, Object>> stop(@RequestParam("sessionId") String sessionId,
                                          @AuthenticationPrincipal Long userId,
                                          ServerWebExchange exchange) {
        // 归属校验（可能查库）+ Redisson 写 stop 标志均为阻塞调用，隔离到弹性线程
        return Blocking.call(() -> {
            ownershipService.require(sessionId,
                    ownershipService.resolve(exchange.getRequest().getHeaders(), userId));
            chatService.stop(sessionId);
            return Map.of("code", 200, "message", "ok");
        });
    }

    /** 越权访问会话 → 403（P0 安全整改） */
    @ExceptionHandler(com.law.backend.service.SessionAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleAccessDenied(
            com.law.backend.service.SessionAccessDeniedException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(NativeAttachmentGateway.PolicyChangedException.class)
    public ResponseEntity<Map<String, String>> handleAttachmentPolicyChanged(NativeAttachmentGateway.PolicyChangedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of("message", e.getMessage()));
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
