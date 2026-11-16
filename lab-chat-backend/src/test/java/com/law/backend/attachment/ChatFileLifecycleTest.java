package com.law.backend.attachment;

import com.law.backend.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatFileLifecycleTest {
    @TempDir Path directory;
    final FileProperties properties = new FileProperties();
    final AttachmentRepository repository = mock(AttachmentRepository.class);
    final AttachmentParser parser = mock(AttachmentParser.class);
    final AttachmentRetrieval retrieval = mock(AttachmentRetrieval.class);
    final AttachmentReview review = mock(AttachmentReview.class);
    final SessionOwnershipService ownership = mock(SessionOwnershipService.class);
    final SessionOwnershipService.Owner owner = new SessionOwnershipService.Owner("u:101", null);
    ChatFileService service() throws Exception {
        properties.setStorageDir(directory.toString());
        return new ChatFileService(properties, new PrivateAttachmentStorage(properties), repository, parser,
                new AttachmentChunker(properties, new AttachmentTokens(properties)), mock(AttachmentEmbedding.class), retrieval, review, ownership);
    }
    AttachmentRepository.FileRecord record(String id, String status) {
        return new AttachmentRepository.FileRecord(id, "synthetic.txt", "txt", status, "text", 100, 1, 1, List.of(), List.of(), "", Long.MAX_VALUE, 100);
    }
    FilePart part(String... chunks) {
        FilePart part = mock(FilePart.class); when(part.filename()).thenReturn("synthetic.txt");
        when(part.content()).thenReturn(Flux.fromArray(chunks).map(text -> new DefaultDataBufferFactory().wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return part;
    }
    @Test void followupReloadsOwnedSessionAttachmentsWithoutResendingIds() throws Exception {
        var file = record("a".repeat(32), "READY");
        when(repository.list(any())).thenReturn(List.of(file)); when(repository.require(any(), eq(file.fileId()))).thenReturn(file);
        when(retrieval.retrieve(any(), anyList(), anyString(), anyInt())).thenReturn(AttachmentContext.empty());
        try (var svc = closable(service())) {
            var first = svc.service.prepareSelected("synthetic-session", owner,
                    svc.service.select("synthetic-session", owner, List.of(file.fileId()), "付款条件是什么"), true, "付款条件是什么", true);
            svc.service.context("synthetic-session", owner, first, "付款条件是什么", 8000, false, text -> {}, () -> false);
            var next = svc.service.prepareSelected("synthetic-session", owner,
                    svc.service.select("synthetic-session", owner, null, "那违约责任呢"), false, "那违约责任呢", true);
            svc.service.context("synthetic-session", owner, next, "该合同的违约责任", 8000, false, text -> {}, () -> false);
            assertEquals(first.files(), next.files());
            assertTrue(svc.service.select("synthetic-session", owner, List.of(), "普通追问").isEmpty());
            verify(retrieval).retrieve(any(), eq(List.of(file)), eq("该合同的违约责任"), eq(8000));
        }
    }
    @Test void oversizedStreamingUploadDeletesPartialDiskFileBeforeReturningError() throws Exception {
        properties.setMaxFileBytes(8);
        try (var svc = closable(service())) {
            assertThrows(IllegalArgumentException.class, () -> svc.service.upload("synthetic-session", owner, part("12345", "67890")).block(Duration.ofSeconds(5)));
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            verify(repository, never()).create(any(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), anyBoolean());
        }
    }
    @Test void uploadReturnsQueuedWhileParserRunsInDedicatedWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(repository.require(any(), anyString())).thenAnswer(inv -> record(inv.getArgument(1), "QUEUED"));
        when(repository.start(anyString(), anyLong())).thenReturn(true);
        when(parser.parse(any(), eq("txt"))).thenAnswer(inv -> {
            started.countDown(); release.await(5, TimeUnit.SECONDS);
            return new AttachmentParser.Parsed("合成条款", List.of(), 1, 1, List.of(), List.of(), "text");
        });
        try (var svc = closable(service())) {
            var result = svc.service.upload("synthetic-session", owner, part("synthetic text")).block(Duration.ofSeconds(5));
            assertNotNull(result); assertEquals("QUEUED", result.status()); assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(Files.exists(directory.resolve(result.fileId())));
        } finally { release.countDown(); }
    }
    @Test void expiryCleanupRemovesOriginalAndRequestsCascadedMetadataPurge() throws Exception {
        String id = "a".repeat(32); Files.writeString(directory.resolve(id), "synthetic");
        when(repository.expired(anyLong())).thenReturn(List.of(id));
        try (var svc = closable(service())) { svc.service.cleanup(); }
        assertFalse(Files.exists(directory.resolve(id))); verify(repository).purge(eq(id), anyLong());
    }
    @Test void explicitInvalidFileOrFailedStatusDoesNotSilentlyBecomeEmptyContext() throws Exception {
        when(repository.require(any(), anyString())).thenThrow(new SessionAccessDeniedException());
        try (var svc = closable(service())) {
            assertThrows(SessionAccessDeniedException.class, () -> svc.service.select("synthetic-session", owner, List.of("a".repeat(32)), "问题"));
            when(repository.list(any())).thenReturn(List.of(record("a".repeat(32), "FAILED")));
            assertThrows(IllegalArgumentException.class, () -> svc.service.prepareSelected("synthetic-session", owner,
                    svc.service.select("synthetic-session", owner, null, "追问"), false, "追问", true));
        }
    }
    @Test void deferredUploadIsAvailableAsOriginalWithoutStartingParser() throws Exception {
        when(repository.require(any(), anyString())).thenAnswer(inv -> record(inv.getArgument(1), "STORED"));
        try (var svc = closable(service())) {
            var saved = svc.service.upload("synthetic-session", owner, part("synthetic original"), true).block(Duration.ofSeconds(5));
            assertNotNull(saved);
            assertEquals("STORED", saved.status());
            assertEquals("synthetic original", Files.readString(svc.service.original("synthetic-session", owner, saved.fileId()).path()));
            verify(repository, never()).start(anyString(), anyLong());
            verifyNoInteractions(parser);
        }
    }
    @Test void oneLocalSendQueuesAndWaitsForDeferredProcessing() throws Exception {
        String id = "a".repeat(32);
        var state = new java.util.concurrent.atomic.AtomicReference<>("STORED");
        when(repository.require(any(), eq(id))).thenAnswer(inv -> record(id, state.get()));
        when(repository.queueStored(any(), eq(id))).thenAnswer(inv -> state.compareAndSet("STORED", "QUEUED"));
        when(repository.start(eq(id), anyLong())).thenAnswer(inv -> state.compareAndSet("QUEUED", "PROCESSING"));
        when(parser.parse(any(), eq("txt"))).thenReturn(new AttachmentParser.Parsed("合成条款", List.of(), 1, 1, List.of(), List.of(), "text"));
        doAnswer(inv -> { state.set("READY"); return null; })
                .when(repository).complete(eq(id), any(), any(), anyList(), anyList(), anyLong());
        try (var svc = closable(service())) {
            var selection = svc.service.select("synthetic-session", owner, List.of(id), "合成问题");
            assertEquals("READY", svc.service.prepareSelected("synthetic-session", owner, selection, true, "合成问题", true).files().get(0).status());
            verify(parser).parse(any(), eq("txt"));
        }
    }
    @Test void deferredDiskAdmissionAccountsForExistingFilesAndConcurrentReservations() throws Exception {
        properties.setStorageDir(directory.toString());
        properties.setMaxPrivateStorageBytes(16);
        var storage = new PrivateAttachmentStorage(properties);
        String first = storage.newId();
        String second = storage.newId();
        storage.reserve(first, 10);
        assertThrows(IllegalArgumentException.class, () -> storage.reserve(second, 7));
        assertFalse(Files.exists(storage.path(second)));
        Files.writeString(storage.path(first), "sample");
        storage.release(first);
        storage.reserve(second, 10);
        storage.release(second);
        assertThrows(IllegalArgumentException.class, () -> storage.reserve(storage.newId(), 11));
    }
    private record CloseableService(ChatFileService service) implements AutoCloseable { public void close() { service.close(); } }
    private CloseableService closable(ChatFileService service) { return new CloseableService(service); }
}
