package com.law.backend.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.law.backend.model.ModelRouteProperties;
import com.law.backend.service.ChatFileService;
import com.law.backend.service.FileProperties;
import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionOwnershipService;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeAttachmentGatewayTest {
    private record Call(String method, String path, byte[] body) {}
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final SessionOwnershipService.Owner owner = new SessionOwnershipService.Owner("u:123", null);
    private final ChatFileService files = mock(ChatFileService.class);
    private final NativeAttachmentProperties config = new NativeAttachmentProperties();
    private final FileProperties limits = new FileProperties();
    private final ObjectMapper json = new ObjectMapper();
    private NativeAttachmentGateway gateway;
    private HttpServer server;
    private volatile boolean reject;
    @TempDir Path folder;

    @BeforeEach void setup() throws Exception {
        var counter = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                calls.add(new Call(method, path, exchange.getRequestBody().readAllBytes()));
                String body = switch (path) {
                    case "/files" -> "{\"id\":\"file-api-synthetic-" + counter.incrementAndGet() + "\"}";
                    case "/chat/completions" -> reject
                            ? "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"discard this answer\"}}]}"
                            : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"synthetic mixed answer\"}}]}";
                    case "/v1/responses" -> "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"synthetic alternate answer\"}]}]}";
                    default -> "{\"deleted\":true}";
                };
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var routes = new ModelRouteProperties();
        for (var protocol : NativeAttachmentProperties.Protocol.values()) {
            var provider = new ModelRouteProperties.Provider();
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            provider.setApiKey("local-fixture");
            routes.getProviders().put(protocol.name(), provider);
            var target = new NativeAttachmentProperties.Target();
            target.setEnabled(true);
            target.setProtocol(protocol);
            target.setProvider(protocol.name());
            target.setLabel(protocol.name());
            target.setModel("synthetic-model");
            config.getTargets().put(protocol.name(), target);
        }
        gateway = new NativeAttachmentGateway(config, routes, limits, new AttachmentParser(limits, null), files);
    }
    @AfterEach void teardown() { server.stop(0); }

    @Test void originalPdfDocAndImageUseBothProtocolsWithoutReplacingSavedBytes() throws Exception {
        Path pdf = folder.resolve("sample.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        Path doc = folder.resolve("sample.doc");
        try (var fs = new POIFSFileSystem(); var stream = Files.newOutputStream(doc)) {
            fs.getRoot().createDocument("WordDocument", new ByteArrayInputStream(new byte[]{1, 2, 3}));
            fs.writeFilesystem(stream);
        }
        Path image = folder.resolve("sample.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var selected = List.of(save("a", "pdf", pdf), save("b", "doc", doc), save("c", "png", image));

        assertEquals("synthetic mixed answer", gateway.answer(request("DEEPSEEK_FILES", selected), "rules", "synthetic question"));
        assertEquals(List.of("/files", "/files", "/files", "/chat/completions", "/files/file-api-synthetic-1",
                "/files/file-api-synthetic-2", "/files/file-api-synthetic-3"), calls.stream().map(Call::path).toList());
        assertTrue(calls.subList(4, 7).stream().allMatch(call -> "DELETE".equals(call.method())));
        for (int i = 0; i < 3; i++) {
            String multipart = new String(calls.get(i).body(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.indexOf("name=\"purpose\"") < multipart.indexOf("name=\"file\""));
            assertTrue(multipart.contains("expires_after[seconds]"));
            assertTrue(multipart.contains(i == 2 ? "user_data" : "rich_layout_recognition"));
            assertTrue(multipart.contains(new String(Files.readAllBytes(List.of(pdf, doc, image).get(i)), StandardCharsets.ISO_8859_1)));
            assertFalse(multipart.contains("sample."));
        }
        var completion = json.readTree(calls.get(3).body());
        assertEquals("high", completion.at("/safety_check/level").asText());
        assertEquals("file-api-synthetic-2", completion.at("/messages/1/content/1/file_id").asText());

        assertEquals("synthetic alternate answer", gateway.answer(request("OPENAI_RESPONSES", selected), "rules", "synthetic question"));
        assertEquals("/v1/responses", calls.get(7).path());
        var responseRequest = json.readTree(calls.get(7).body());
        assertTrue(responseRequest.path("store").isBoolean());
        assertFalse(responseRequest.path("store").asBoolean());
        assertEquals("attachment-2.doc", responseRequest.at("/input/0/content/1/filename").asText());
        String inlineDoc = responseRequest.at("/input/0/content/1/file_data").asText();
        assertArrayEquals(Files.readAllBytes(doc), Base64.getDecoder().decode(inlineDoc.substring(inlineDoc.indexOf(',') + 1)));
        assertEquals("input_image", responseRequest.at("/input/0/content/2/type").asText());
        assertEquals(8, calls.size());
    }

    @Test void refusalCleansUpWithoutRetryingElsewhere() throws Exception {
        Path image = folder.resolve("sample.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        reject = true;
        var request = request("DEEPSEEK_FILES", List.of(save("a", "png", image)));
        var error = assertThrows(IllegalArgumentException.class, () -> gateway.answer(request, "rules", "synthetic question"));
        assertEquals("所选模型未通过本轮内容审核", error.getMessage());
        assertEquals(List.of("/files", "/chat/completions", "/files/file-api-synthetic-1"), calls.stream().map(Call::path).toList());
    }

    @Test void revocationAndFileSizeAreCheckedBeforeSending() throws Exception {
        Path image = folder.resolve("sample.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var file = save("a", "png", image);
        var request = request("OPENAI_RESPONSES", List.of(file));
        config.getTargets().get("OPENAI_RESPONSES").setMaxBytes(1);
        assertThrows(IllegalArgumentException.class, () -> request("OPENAI_RESPONSES", List.of(file)));
        when(files.original("synthetic-session", owner, file.fileId())).thenThrow(new SessionAccessDeniedException());
        assertThrows(SessionAccessDeniedException.class, () -> gateway.answer(request, "rules", "synthetic question"));
        assertTrue(calls.isEmpty());
    }

    private AttachmentRepository.FileRecord save(String key, String ext, Path path) throws Exception {
        var record = new AttachmentRepository.FileRecord(key.repeat(32), path.getFileName().toString(), ext, "STORED", "text",
                0, 0, 0, List.of(), List.of(), "", Long.MAX_VALUE, Files.size(path));
        when(files.original("synthetic-session", owner, record.fileId())).thenReturn(new ChatFileService.Original(record, path));
        return record;
    }

    private NativeAttachmentGateway.Request request(String target, List<AttachmentRepository.FileRecord> selected) {
        var capability = gateway.capabilities().stream().filter(c -> c.label().equals(target)).findFirst().orElseThrow();
        return gateway.prepare(capability.id(), "synthetic-session", owner, selected);
    }

    @Test void changingModelInvalidatesThePreviouslySelectedRecipient() {
        String selected = gateway.capabilities().stream().filter(c -> c.label().equals("OPENAI_RESPONSES")).findFirst().orElseThrow().id();
        config.getTargets().get("OPENAI_RESPONSES").setModel("synthetic-model-update");
        assertTrue(gateway.capabilities().stream().noneMatch(c -> c.id().equals(selected)));
        assertThrows(IllegalArgumentException.class, () -> gateway.prepare(selected, "synthetic-session", owner, List.of()));
        assertTrue(calls.isEmpty());
    }
}
