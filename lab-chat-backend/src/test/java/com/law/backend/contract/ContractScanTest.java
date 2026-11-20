package com.law.backend.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.law.backend.attachment.AttachmentReviewModel;
import com.law.backend.attachment.AttachmentTokens;
import com.law.backend.controller.ContractScanController;
import com.law.backend.model.ModelRouteProperties;
import com.law.backend.service.FileProperties;
import com.law.backend.service.ImageCaptionService;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.reactive.result.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.law.backend.contract.ContractScanService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractScanTest {
    private static final long OWNER = 71, OTHER = 82;
    private static final String ORDINARY = "Synthetic contract specifies payment within 30 days.";
    private static final String FINAL_PROVISION = "Synthetic appendix states the 30-day deadline excludes disputed invoices.";
    private static final String EMPTY_REVIEW = "{\"risks\":[],\"terms\":[]}";
    @TempDir Path directory;
    private ObjectMapper json;
    private ContractScanProperties config;
    private FileProperties files;
    private ModelRouteProperties routes;
    private ImageCaptionService vision;
    private AttachmentReviewModel model;
    private ContractScanStore store;
    private ContractScanDocuments documents;
    private ContractScanService service;
    private ContractScanReview review;

    @BeforeEach void setUp() throws Exception {
        json = new ObjectMapper();
        config = new ContractScanProperties();
        files = new FileProperties();
        files.setStorageDir(directory.toString());
        routes = new ModelRouteProperties();
        var route = new ModelRouteProperties.Provider();
        route.setBaseUrl("https://ocr.example.invalid");
        routes.getProviders().put(files.getVlProvider(), route);
        routes.getRoutes().put("legal", files.getVlProvider() + "/synthetic-review");
        vision = mock(ImageCaptionService.class);
        model = mock(AttachmentReviewModel.class);
        store = new ContractScanStore(files, json);
        documents = new ContractScanDocuments(config, vision);
        service = new ContractScanService(store, documents, config, routes, files);
        review = new ContractScanReview(model, new AttachmentTokens(files), files, json);
        when(model.complete(anyString(), anyString())).thenReturn(EMPTY_REVIEW);
    }

    @Test void fiftyPhotoPagesRetainOrderAndOnlyFailedPageUsesOcrAgain() throws Exception {
        byte[] photoBytes = photo(112, 180);
        var specs = new ArrayList<UploadSpec>();
        for (int i = 0; i < 50; i++) specs.add(new UploadSpec("synthetic-" + (50 - i) + ".png", "png", photoBytes.length, true));
        var draft = create(specs);
        for (var file : draft.files()) upload(draft.summary().id(), file.id(), photoBytes);
        var calls = new AtomicInteger();
        when(vision.transcribe(any(), eq("image/png"))).thenAnswer(invocation -> calls.incrementAndGet() == 17 ? "[无法辨认]" : ORDINARY);
        service.start(OWNER, draft.summary().id(), consent());
        var queued = service.take();
        assertNotNull(queued);
        // Simulate a process restart after the first page checkpoint.
        queued.pages = 50;
        store.save(queued);
        store.write(store.source(queued, 1), documents.photo(store.asset(queued, queued.inputs.get(0)), "png"));
        run();
        var partial = service.detail(OWNER, draft.summary().id());
        assertEquals("synthetic-50.png", partial.files().get(0).name());
        assertEquals("synthetic-1.png", partial.files().get(49).name());
        assertEquals("PARTIAL", partial.summary().status());
        assertEquals(49, partial.pages().stream().filter(PageView::scanned).count());
        assertNotNull(partial.pages().get(16).warning());
        assertEquals(50, calls.get());
        var better = photo(220, 350);
        var pending = service.prepareReplacement(OWNER, draft.summary().id(), 17,
                new Replacement(new UploadSpec("synthetic-clear.png", "png", better.length, true), consent().revision()));
        upload(draft.summary().id(), pending.replacement().id(), better);
        service.retry(OWNER, draft.summary().id(), 17, consent());
        run();
        var complete = service.detail(OWNER, draft.summary().id());
        assertEquals("READY", complete.summary().status());
        assertEquals(50, complete.pages().stream().filter(PageView::reviewed).count());
        assertEquals("synthetic-clear.png", complete.files().get(16).name());
        assertArrayEquals(better, Files.readAllBytes(service.preview(OWNER, draft.summary().id(), 17).path()));
        assertEquals(51, calls.get());
        verify(model, times(50)).complete(anyString(), anyString());
    }

    @Test void largePdfChecksLastPageAndBindsRiskToVerifiedQuote() throws Exception {
        byte[] pdf = largePdf();
        assertTrue(pdf.length > 20 * 1024 * 1024);
        when(model.complete(anyString(), anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(1);
            if (input.contains("页号：200\n")) {
                var risk = new ContractScanStore.Risk("中", "合成付款例外", "核对付款条件",
                        List.of(new ContractScanStore.Evidence(200, FINAL_PROVISION)));
                return json.writeValueAsString(Map.of("risks", List.of(risk), "terms", List.of()));
            }
            return EMPTY_REVIEW;
        });
        var hashes = new ArrayList<String>();
        var sha256 = java.security.MessageDigest.getInstance("SHA-256");
        for (int offset = 0; offset < pdf.length; offset += CHUNK_BYTES) {
            sha256.update(pdf, offset, Math.min(CHUNK_BYTES, pdf.length - offset));
            hashes.add(java.util.HexFormat.of().formatHex(sha256.digest()));
        }
        var draft = create(List.of(new UploadSpec("synthetic-long.pdf", "pdf", pdf.length, false, hashes)));
        String id = draft.summary().id(), asset = draft.files().get(0).id();
        byte[] first = Arrays.copyOf(pdf, CHUNK_BYTES);
        assertEquals(CHUNK_BYTES, service.upload(OWNER, id, asset, 0, first).received());
        assertEquals(CHUNK_BYTES, service.upload(OWNER, id, asset, 0, first).received());
        first[0] = 0;
        assertEquals(409, assertThrows(Problem.class, () -> service.upload(OWNER, id, asset, 0, first)).status().value());
        upload(id, asset, pdf);
        assertEquals(409, assertThrows(Problem.class, () -> service.start(OWNER, id, new Consent("obsolete"))).status().value());
        service.start(OWNER, id, consent());
        run();
        var result = service.detail(OWNER, id);
        assertEquals("READY", result.summary().status());
        assertEquals(200, result.pages().stream().filter(PageView::reviewed).count());
        assertEquals(200, result.risks().get(0).evidence().get(0).page());
        assertArrayEquals(pdf, Files.readAllBytes(service.download(OWNER, id, asset).path()));
        assertTrue(service.preview(OWNER, id, 200).size() > 10);
        verifyNoInteractions(vision);
    }

    @Test void policyChangesPauseBeforeAnyNewDataTransfer() throws Exception {
        byte[] bytes = photo(112, 180);
        var draft = create(List.of(new UploadSpec("synthetic.png", "png", bytes.length, true)));
        upload(draft.summary().id(), draft.files().get(0).id(), bytes);
        service.start(OWNER, draft.summary().id(), consent());
        routes.getProviders().get(files.getVlProvider()).setBaseUrl("https://alternate.example.invalid");
        var restarted = new ContractScanService(new ContractScanStore(files, json), documents, config, routes, files);
        assertNull(restarted.take());
        assertEquals("PAUSED", restarted.detail(OWNER, draft.summary().id()).summary().status());
        assertEquals(409, assertThrows(Problem.class, () -> restarted.start(OWNER, draft.summary().id(), consent())).status().value());
        assertEquals("QUEUED", restarted.start(OWNER, draft.summary().id(), new Consent(restarted.policy().revision())).summary().status());
        verifyNoInteractions(vision, model);
    }

    @Test void webEndpointHandlesBinaryChunksAndKeepsTaskPrivate() throws Exception {
        byte[] bytes = photo(112, 180);
        var draft = create(List.of(new UploadSpec("synthetic.png", "png", bytes.length, true)));
        String path = "/tools/contract-scans/jobs/" + draft.summary().id();
        String asset = draft.files().get(0).id();
        var client = WebTestClient.bindToController(new ContractScanController(service))
                .argumentResolvers(configurer -> configurer.addCustomResolver(new AuthenticationPrincipalArgumentResolver(ReactiveAdapterRegistry.getSharedInstance())))
                .webFilter((exchange, chain) -> {
                    long id = Long.parseLong(exchange.getRequest().getHeaders().getFirst("X-Test-Owner"));
                    var auth = new UsernamePasswordAuthenticationToken(id, null, List.<GrantedAuthority>of());
                    return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                }).build();
        client.put().uri(path + "/uploads/" + asset + "?offset=0").header("X-Test-Owner", Long.toString(OWNER))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).bodyValue(bytes).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.received").isEqualTo(bytes.length);
        client.get().uri(path + "/files/" + asset).header("X-Test-Owner", Long.toString(OTHER)).exchange().expectStatus().isNotFound();
        client.get().uri(path + "/files/" + asset).header("X-Test-Owner", Long.toString(OWNER)).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "private, no-store").expectBody(byte[].class).isEqualTo(bytes);
        assertEquals(409, assertThrows(Problem.class, () -> create(List.of(new UploadSpec("synthetic.png", "png", bytes.length, true)))).status().value());
        service.delete(OWNER, draft.summary().id());
        assertFalse(Files.exists(store.jobPath(draft.summary().id())));
        assertTrue(service.list(OWNER).isEmpty());
    }

    @Test void reportRejectsInventedQuotesAndCrossPageNeedsTwoVerifiedSources() throws Exception {
        when(model.complete(anyString(), anyString())).thenReturn("""
                {"risks":[{"severity":"高","title":"合成风险","suggestion":"待核对","evidence":[{"page":1,"quote":"不存在的审查引文"}]}],"terms":[]}
                """);
        assertThrows(java.io.IOException.class, () -> review.page("中立", 1, ORDINARY, "", "", () -> true));
        var a = new ContractScanStore.Evidence(1, ORDINARY);
        var b = new ContractScanStore.Evidence(2, FINAL_PROVISION);
        when(model.complete(anyString(), anyString())).thenReturn(json.writeValueAsString(Map.of("risks", List.of(
                new ContractScanStore.Risk("中", "付款条件与例外", "核对两处原文", List.of(a, b))))));
        var cross = review.cross("中立", List.of(new ContractScanStore.Term("付款", a), new ContractScanStore.Term("期限", b)),
                Map.of(1, ORDINARY, 2, FINAL_PROVISION), () -> true);
        assertTrue(cross.complete());
        assertEquals(List.of(a, b), cross.risks().get(0).evidence());
    }

    private Consent consent() { return new Consent(service.policy().revision()); }

    private Detail create(List<UploadSpec> specs) throws Exception {
        return service.create(OWNER, new CreateRequest(specs, "中立", consent().revision()));
    }

    private void upload(String id, String asset, byte[] contents) throws Exception {
        for (int offset = 0; offset < contents.length; offset += CHUNK_BYTES) {
            int end = Math.min(contents.length, offset + CHUNK_BYTES);
            assertEquals(end, service.upload(OWNER, id, asset, offset, Arrays.copyOfRange(contents, offset, end)).received());
        }
    }

    private void run() throws Exception {
        var worker = new ContractScanWorker(store, service, documents, review);
        try { worker.process(service.take()); }
        finally { worker.close(); }
    }

    private static byte[] photo(int width, int height) throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        }
    }

    private static byte[] largePdf() throws Exception {
        try (var document = new PDDocument(); var buffer = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 200; page++) {
                PDPage current = new PDPage();
                document.addPage(current);
                try (var content = new PDPageContentStream(document, current)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    content.newLineAtOffset(36, 720);
                    content.showText(page == 200 ? FINAL_PROVISION : ORDINARY);
                    content.endText();
                }
            }
            var padding = new PDStream(document);
            try (var stream = padding.createOutputStream()) {
                byte[] megabyte = new byte[1024 * 1024];
                for (int i = 0; i < 21; i++) stream.write(megabyte);
            }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("SyntheticPadding"), padding.getCOSObject());
            document.save(buffer);
            return buffer.toByteArray();
        }
    }
}
