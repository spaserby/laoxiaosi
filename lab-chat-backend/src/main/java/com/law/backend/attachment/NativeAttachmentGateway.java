package com.law.backend.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.law.backend.model.ModelRouteProperties;
import com.law.backend.service.ChatFileService;
import com.law.backend.service.FileProperties;
import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionOwnershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;

/** 必须由本轮选择的目标接收原件。供应商文件标识只在一次调用期间使用。 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(NativeAttachmentProperties.class)
public class NativeAttachmentGateway {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(2);
    private final NativeAttachmentProperties properties;
    private final ModelRouteProperties models;
    private final FileProperties limits;
    private final AttachmentParser parser;
    private final ChatFileService files;

    public record Capability(String id, String label, Set<String> formats, long maxBytes, int maxPdfPages) {}
    public record Policy(String revision, String recipient, Set<String> formats, Set<String> imageFormats, int maxFiles,
                         long maxTotalBytes, long maxDocumentBytes, long maxImageBytes, int maxPdfPages) {
        public long fileLimit(String extension) {
            if (!formats.contains(extension)) throw new IllegalArgumentException("本轮包含不支持的附件格式，请移除后重试");
            return imageFormats.contains(extension) ? maxImageBytes : maxDocumentBytes;
        }
    }
    public static final class PolicyChangedException extends IllegalArgumentException {
        public PolicyChangedException() { super("附件处理设置已更新，请查看新的提示后再次发送"); }
    }
    public record Request(String targetId, String sessionId, SessionOwnershipService.Owner owner, List<String> ids) {}

    public boolean legacyModesEnabled() { return properties.isLegacyModesEnabled(); }

    public java.util.Optional<Policy> currentPolicy() {
        return properties.getTargets().entrySet().stream()
                .filter(entry -> entry.getKey().equals(properties.getDefaultTarget()) && available(entry.getValue()))
                .map(this::policy).findFirst();
    }

    public Policy confirmPolicy(String revision) {
        Policy policy = currentPolicy().orElseThrow(() -> revision == null
                ? new IllegalArgumentException("附件处理暂不可用，请稍后重试或仅发送文字")
                : new PolicyChangedException());
        if (!policy.revision().equals(revision)) throw new PolicyChangedException();
        return policy;
    }

    public List<Capability> capabilities() {
        return properties.getTargets().entrySet().stream().filter(entry -> available(entry.getValue()))
                .map(this::policy).map(policy -> new Capability(policy.revision(), policy.recipient(), policy.formats(),
                        policy.maxTotalBytes(), policy.maxPdfPages())).toList();
    }

    public Request prepare(String id, String sessionId, SessionOwnershipService.Owner owner,
                           List<AttachmentRepository.FileRecord> selected) {
        var policy = policy(requireTarget(id));
        if (selected.isEmpty()) throw new IllegalArgumentException("请添加要直传的附件");
        if (selected.size() > policy.maxFiles()) throw new IllegalArgumentException("本轮最多发送 " + policy.maxFiles() + " 个附件");
        long total = 0;
        for (var file : selected) {
            long limit = policy.fileLimit(file.extension());
            if (file.sizeBytes() > limit) throw new IllegalArgumentException("「" + file.name() + "」超过单文件上限 " + size(limit));
            total += file.sizeBytes();
        }
        if (total > policy.maxTotalBytes())
            throw new IllegalArgumentException("本轮附件共 " + size(total) + "，最多 " + size(policy.maxTotalBytes()) + "，请移除文件或分次发送");
        return new Request(id, sessionId, owner, selected.stream().map(AttachmentRepository.FileRecord::fileId).toList());
    }

    public String answer(Request request, String systemPrompt, String question) {
        var entry = requireTarget(request.targetId());
        var target = entry.getValue();
        int maxPages = policy(entry).maxPdfPages();
        if (!slots.tryAcquire()) throw new IllegalArgumentException("附件直传繁忙，请稍后重试");
        try {
            int pages = 0;
            for (String id : request.ids()) {
                checkInterrupted();
                var original = files.original(request.sessionId(), request.owner(), id);
                try { pages += parser.validateOriginal(original.path(), original.metadata().extension()); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("「" + original.metadata().name() + "」" + e.getMessage()); }
                if (pages > maxPages)
                    throw new IllegalArgumentException("加入「" + original.metadata().name() + "」后 PDF 合计 " + pages
                            + " 页，最多 " + maxPages + " 页，请拆分或分次发送");
            }
            var provider = models.getProviders().get(target.getProvider());
            String url = provider.getBaseUrl().replaceAll("/+$", "");
            var client = client(target.getProtocol() == NativeAttachmentProperties.Protocol.OPENAI_RESPONSES ? url + "/v1" : url,
                    provider.getApiKey(), Duration.ofSeconds(125));
            String result = switch (target.getProtocol()) {
                case DEEPSEEK_FILES -> deepseek(client, url, provider.getApiKey(), target.getModel(), request, systemPrompt, question);
                case OPENAI_RESPONSES -> openai(client, target.getModel(), request, systemPrompt, question);
            };
            if (result.isBlank()) throw new IllegalArgumentException("所选模型没有返回可用答案");
            return result.replace("\u0000", "");
        } catch (SessionAccessDeniedException | IllegalArgumentException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            // HTTP 异常可能携带供应商响应、文件名和请求内容。
            log.warn("附件直传调用失败：type={}", e.getClass().getSimpleName());
            throw new IllegalArgumentException("未完成附件处理，请稍后重试");
        } finally {
            slots.release();
        }
    }

    private String deepseek(RestClient client, String url, String key, String model, Request request,
                            String prompt, String question) {
        List<String> remoteIds = new ArrayList<>();
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            for (String id : request.ids()) {
                checkInterrupted();
                var file = files.original(request.sessionId(), request.owner(), id);
                var form = new MultipartBodyBuilder();
                var extension = file.metadata().extension();
                form.part("purpose", Set.of("pdf", "doc", "docx").contains(extension) ? "rich_layout_recognition" : "user_data");
                form.part("expires_after[anchor]", "created_at");
                form.part("expires_after[seconds]", "3600");
                form.part("file", new FileSystemResource(file.path()), MediaType.parseMediaType(mime(file)))
                        .filename(name(file, content.size()));
                JsonNode response;
                try {
                    response = client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(form.build()).retrieve().body(JsonNode.class);
                } catch (RestClientException e) {
                    log.warn("供应商未完成附件上传：type={}", e.getClass().getSimpleName());
                    throw new IllegalArgumentException("「" + file.metadata().name() + "」未能处理，请检查文件后重试");
                }
                String remoteId = response == null ? "" : response.path("id").asText();
                if (!remoteId.matches("file-[A-Za-z0-9_-]{1,128}")) throw new IllegalStateException("Invalid file identifier");
                remoteIds.add(remoteId);
                content.add(Map.of("type", "file", "file_id", remoteId));
            }
            checkInterrupted();
            // 如果上传期间原件已被删除，清理临时文件并取消后续推理。
            request.ids().forEach(id -> files.original(request.sessionId(), request.owner(), id));
            content.add(Map.of("type", "text", "text", question));
            JsonNode choice = post(client, "/chat/completions", Map.of("model", model, "stream", false,
                    "thinking", Map.of("type", "disabled"),
                    "max_tokens", limits.getOutputReserveTokens(), "safety_check", Map.of("level", "high"),
                    "messages", List.of(Map.of("role", "system", "content", prompt), Map.of("role", "user", "content", content))))
                    .path("choices").path(0);
            String reason = choice.path("finish_reason").asText();
            if ("content_filter".equals(reason) || !choice.path("message").path("refusal").asText("").isBlank())
                throw new IllegalArgumentException("所选模型未通过本轮内容审核");
            if (!"stop".equals(reason)) throw new IllegalArgumentException("所选模型未完整生成回答，请缩小问题范围后重试");
            return choice.path("message").path("content").asText("");
        } finally {
            if (!remoteIds.isEmpty()) {
                var cleaner = client(url, key, Duration.ofSeconds(4));
                boolean interrupted = Thread.interrupted();
                try {
                    for (String id : remoteIds) {
                        try { cleaner.delete().uri("/files/{id}", id).retrieve().toBodilessEntity(); }
                        catch (Exception e) { log.warn("供应商附件删除暂未确认，等待上传时设置的过期时间"); }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private String openai(RestClient client, String model, Request request, String prompt, String question) throws IOException {
        List<Map<String, Object>> content = new ArrayList<>();
        for (String id : request.ids()) {
            checkInterrupted();
            var file = files.original(request.sessionId(), request.owner(), id);
            String data = "data:" + mime(file) + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file.path()));
            content.add(image(file) ? Map.of("type", "input_image", "image_url", data, "detail", "auto")
                    : Map.of("type", "input_file", "file_data", data, "filename", name(file, content.size())));
        }
        checkInterrupted();
        request.ids().forEach(id -> files.original(request.sessionId(), request.owner(), id));
        content.add(Map.of("type", "input_text", "text", question));
        JsonNode result = post(client, "/responses", Map.of("model", model, "instructions", prompt,
                "max_output_tokens", limits.getOutputReserveTokens(), "store", false,
                "input", List.of(Map.of("role", "user", "content", content))));
        if (!"completed".equals(result.path("status").asText()))
            throw new IllegalArgumentException("所选模型未完整生成回答，请缩小问题范围后重试");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : result.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode block : item.path("content")) {
                if ("refusal".equals(block.path("type").asText()))
                    throw new IllegalArgumentException("所选模型未通过本轮内容审核");
                if ("output_text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private static JsonNode post(RestClient client, String path, Map<String, Object> body) {
        JsonNode result = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (result == null) throw new IllegalStateException("Empty model response");
        return result;
    }

    private Map.Entry<String, NativeAttachmentProperties.Target> requireTarget(String id) {
        return properties.getTargets().entrySet().stream().filter(entry -> available(entry.getValue()))
                .filter(entry -> selectionId(entry).equals(id))
                .findFirst().orElseThrow(PolicyChangedException::new);
    }

    private Policy policy(Map.Entry<String, NativeAttachmentProperties.Target> entry) {
        var target = entry.getValue();
        long total = maxBytes(target);
        return new Policy(selectionId(entry), target.getLabel(), AttachmentParser.EXTENSIONS, AttachmentParser.IMAGE_EXTENSIONS,
                limits.getMaxFilesPerTurn(), total, Math.min(total, limits.getMaxFileBytes()), Math.min(total, limits.getMaxImageBytes()),
                Math.min(target.getMaxPdfPages(), limits.getMaxPages()));
    }

    private String selectionId(Map.Entry<String, NativeAttachmentProperties.Target> entry) {
        var target = entry.getValue();
        var provider = models.getProviders().get(target.getProvider());
        // 页面打开后发生端点或模型切换时，旧页面的接收方确认随之失效。
        String revision = String.join("\n", entry.getKey(), target.getProvider(), target.getProtocol().name(),
                target.getLabel(), target.getModel(), provider.getBaseUrl(), Long.toString(maxBytes(target)),
                Integer.toString(target.getMaxPdfPages()), Integer.toString(limits.getMaxFilesPerTurn()),
                Long.toString(limits.getMaxFileBytes()), Long.toString(limits.getMaxImageBytes()), Integer.toString(limits.getMaxPages()));
        return entry.getKey() + "-" + UUID.nameUUIDFromBytes(revision.getBytes(StandardCharsets.UTF_8));
    }

    private boolean available(NativeAttachmentProperties.Target target) {
        if (target == null || !target.isEnabled() || target.getProtocol() == null || !hasText(target.getModel())
                || !hasText(target.getLabel()) || target.getMaxBytes() < 1 || target.getMaxPdfPages() < 1) return false;
        var provider = models.getProviders().get(target.getProvider());
        if (provider == null || !hasText(provider.getApiKey()) || !hasText(provider.getBaseUrl())) return false;
        try {
            URI url = URI.create(provider.getBaseUrl());
            return url.getHost() != null && url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null
                    && ("https".equals(url.getScheme()) || ("http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost())));
        } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static long maxBytes(NativeAttachmentProperties.Target target) {
        return target.getProtocol() == NativeAttachmentProperties.Protocol.OPENAI_RESPONSES
                ? Math.min(target.getMaxBytes(), 8 * 1024 * 1024L) : target.getMaxBytes();
    }
    private static String size(long bytes) { return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576d); }

    private static RestClient client(String url, String key, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HTTP);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(key)).build();
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static String name(ChatFileService.Original file, int index) { return "attachment-" + (index + 1) + "." + file.metadata().extension(); }
    private static boolean image(ChatFileService.Original file) { return mime(file).startsWith("image/"); }
    private static String mime(ChatFileService.Original file) {
        return switch (file.metadata().extension()) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            default -> AttachmentParser.mime(file.metadata().extension());
        };
    }
}
