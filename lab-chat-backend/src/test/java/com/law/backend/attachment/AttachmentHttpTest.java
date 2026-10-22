package com.law.backend.attachment;

import com.law.backend.controller.ChatController;
import com.law.backend.service.*;
import com.law.backend.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentHttpTest {
    final ChatFileService files = mock(ChatFileService.class);
    final SessionOwnershipService ownership = mock(SessionOwnershipService.class);
    final SessionOwnershipService.Owner owner = new SessionOwnershipService.Owner("g:syntheticGuestKey", "syntheticGuestKey");
    WebTestClient client;
    @BeforeEach void setup() {
        when(ownership.resolve(any(), isNull())).thenReturn(owner);
        client = WebTestClient.bindToController(new ChatController(mock(ChatService.class), mock(RedissonClient.class),
                mock(AuthService.class), files, ownership)).build();
    }
    @Test void multipartUploadBindsSessionAndFilePartAndReturnsQueuedMetadata() {
        var result = new ChatFileService.UploadedFile("a".repeat(32), "synthetic.txt", 0, false, "text", List.of(), "QUEUED", 0, 0, List.of(), List.of(), "", Long.MAX_VALUE);
        when(files.upload(eq("synthetic-session"), eq(owner), any(FilePart.class))).thenReturn(Mono.just(result));
        var multipart = new MultipartBodyBuilder();
        multipart.part("files", new ByteArrayResource("synthetic content".getBytes())).filename("synthetic.txt");
        client.post().uri("/chat/files?sessionId=synthetic-session").contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipart.build()).exchange().expectStatus().isOk().expectBody().jsonPath("$[0].status").isEqualTo("QUEUED");
        verify(files).upload(eq("synthetic-session"), eq(owner), any(FilePart.class));
    }
    @Test void statusAndSourceAreNotPublicEvenWithKnownIds() {
        when(files.list("synthetic-session", owner)).thenThrow(new SessionAccessDeniedException());
        when(files.source("synthetic-session", owner, "a".repeat(32), 0)).thenThrow(new SessionAccessDeniedException());
        client.get().uri("/chat/files?sessionId=synthetic-session").exchange().expectStatus().isForbidden();
        client.get().uri("/chat/files/" + "a".repeat(32) + "/source?sessionId=synthetic-session&parentId=0").exchange().expectStatus().isForbidden();
    }
    @Test void mutationsReceiveCurrentIdentityAndRejectCrossSessionUse() {
        doThrow(new SessionAccessDeniedException()).when(files).delete("other-session", owner, "a".repeat(32));
        when(files.retry("other-session", owner, "a".repeat(32))).thenThrow(new SessionAccessDeniedException());
        client.delete().uri("/chat/files/" + "a".repeat(32) + "?sessionId=other-session").exchange().expectStatus().isForbidden();
        client.post().uri("/chat/files/" + "a".repeat(32) + "/retry?sessionId=other-session").exchange().expectStatus().isForbidden();
    }
}
