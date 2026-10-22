package com.law.backend.service;

import com.law.backend.attachment.*;
import com.law.backend.memory.SessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 复现普通问答在附件预处理阶段抢先写入错误标题的真实调用链。 */
class SessionTitleRegressionTest {
    @Test
    void uploadRegistrationUsesNeutralPendingTitle() {
        var mapper = mock(SessionMapper.class);
        var owner = new SessionOwnershipService.Owner("u:101", null);
        when(mapper.findOwnerKey("synthetic-upload")).thenReturn(null, owner.key());
        new SessionOwnershipService(mapper).register("synthetic-upload", owner);
        verify(mapper).upsert(eq("synthetic-upload"), eq("新对话"), anyLong(), anyLong(), eq(owner.key()));
    }

    @Test
    void titleTruncationDoesNotSplitUnicodeCharacters() {
        assertEquals("新对话", SessionIndexService.titleOf("  "));
        String text = "合成".repeat(9) + "😀标题后缀";
        assertEquals("合成".repeat(9) + "😀标", SessionIndexService.titleOf(text));
    }

    @Test
    void ordinaryQuestionIsNotRegisteredWithAttachmentPlaceholder() {
        var mapper = mock(SessionMapper.class);
        var owner = new SessionOwnershipService.Owner("u:101", null);
        when(mapper.findOwnerKey("synthetic-session")).thenReturn(null, owner.key());
        var repository = mock(AttachmentRepository.class);
        when(repository.list(any())).thenReturn(List.of());
        var ownership = new SessionOwnershipService(mapper);
        var files = new ChatFileService(new FileProperties(), mock(PrivateAttachmentStorage.class), repository,
                mock(AttachmentParser.class), mock(AttachmentChunker.class), mock(AttachmentEmbedding.class),
                mock(AttachmentRetrieval.class), mock(AttachmentReview.class), ownership);
        String question = "合成测试：解除合同需要满足什么条件";
        try {
            files.turn("synthetic-session", owner, List.of(), question);
            new SessionIndexService(mapper).touch("synthetic-session", question, owner.key());
            var titles = ArgumentCaptor.forClass(String.class);
            verify(mapper, times(2)).upsert(eq("synthetic-session"), titles.capture(), anyLong(), anyLong(), eq(owner.key()));
            assertEquals(question, titles.getAllValues().get(0), "首次登记必须使用提问标题，不能被附件预处理抢先写死");
        } finally { files.close(); }
    }
}
