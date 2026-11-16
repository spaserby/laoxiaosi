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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
    private static final Set<String> FORMATS = Set.of("doc", "docx", "pdf", "png", "jpg", "jpeg", "webp");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(2);
    private final NativeAttachmentProperties properties;
    private final ModelRouteProperties models;
    private final FileProperties limits;
    private final AttachmentParser parser;
    private final ChatFileService files;

    public record Capability(String id, String label, Set<String> formats, long maxBytes, int maxPdfPages) {}
    public record Request(String targetId, String sessionId, SessionOwnershipService.Owner owner, List<String> ids) {}

    public List<Capability> capabilities() {
        return properties.getTargets().entrySet().stream().filter(entry -> available(entry.getValue()))
                .map(entry -> new Capability(selectionId(entry), entry.getValue().getLabel(), FORMATS,
                        maxBytes(entry.getValue()), entry.getValue().getMaxPdfPages())).toList();
    }

    public Request prepare(String id, String sessionId, SessionOwnershipService.Owner owner,
                           List<AttachmentRepository.FileRecord> selected) {
        var target = requireTarget(id);
        if (selected.isEmpty()) throw new IllegalArgumentException("请添加要直传的附件");
        if (selected.stream().anyMatch(file -> !FORMATS.contains(file.extension())))
            throw new IllegalArgumentException("原件直传支持 PDF、DOC、DOCX 和常见图片，TXT / Markdown 请使用片段问答");
        if (selected.stream().mapToLong(AttachmentRepository.FileRecord::sizeBytes).sum() > maxBytes(target))
            throw new IllegalArgumentException("本轮附件总大小超过所选模型的直传限额");
        return new Request(id, sessionId, owner, selected.stream().map(AttachmentRepository.FileRecord::fileId).toList());
    }

    public String answer(Request request, String systemPrompt, String question) {
        var target = requireTarget(request.targetId());
        if (!slots.tryAcquire()) throw new IllegalArgumentException("附件直传繁忙，请稍后重试");
        try {
            int pages = 0;
            for (String id : request.ids()) {
                checkInterrupted();
                var original = files.original(request.sessionId(), request.owner(), id);
                pages += parser.validateOriginal(original.path(), original.metadata().extension());
                if (pages > target.getMaxPdfPages()) throw new IllegalArgumentException("本轮 PDF 总页数超过直传限额，请拆分或使用片段问答");
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
            throw new IllegalArgumentException("所选模型未完成附件处理，请重试或主动切换问答方式");
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
                form.part("purpose", image(file) ? "user_data" : "rich_layout_recognition");
                form.part("expires_after[anchor]", "created_at");
                form.part("expires_after[seconds]", "3600");
                form.part("file", new FileSystemResource(file.path()), MediaType.parseMediaType(mime(file)))
                        .filename(name(file, content.size()));
                JsonNode response = client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(form.build()).retrieve().body(JsonNode.class);
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

    private NativeAttachmentProperties.Target requireTarget(String id) {
        return properties.getTargets().entrySet().stream().filter(entry -> available(entry.getValue()))
                .filter(entry -> selectionId(entry).equals(id))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选原件直传模型不可用，请刷新后重新选择"));
    }

    private String selectionId(Map.Entry<String, NativeAttachmentProperties.Target> entry) {
        var target = entry.getValue();
        var provider = models.getProviders().get(target.getProvider());
        // 页面打开后发生端点或模型切换时，旧页面的接收方确认随之失效。
        String revision = String.join("\n", entry.getKey(), target.getProvider(), target.getProtocol().name(),
                target.getLabel(), target.getModel(), provider.getBaseUrl());
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
            default -> AttachmentParser.mime(file.metadata().extension());
        };
    }
}
