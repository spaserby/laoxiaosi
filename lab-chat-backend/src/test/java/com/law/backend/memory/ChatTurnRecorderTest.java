package com.law.backend.memory;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTurnRecorderTest {
    @Test void cancellationDuringBeginStillFinalizesCommittedUser() {
        var service=mock(ConversationTurnService.class);
        var recorder=new ChatTurnRecorder(service);
        var turn=new ConversationTurnService.Turn("synthetic","u:900001","synthetic-turn");
        when(service.begin("synthetic","u:900001","question")).thenAnswer(call -> {
            recorder.mark("INTERRUPTED"); recorder.persist(); return turn;
        });
        assertThrows(CancellationException.class, () -> recorder.begin("synthetic","u:900001","question"));
        verify(service).finish(eq(turn),eq(""),eq("INTERRUPTED"),contains("\"version\":1"));
    }
    @Test void retryDiscardsFirstAttemptAndStopKeepsPartialSecondAnswer() {
        var service=mock(ConversationTurnService.class);
        var turn=new ConversationTurnService.Turn("synthetic","u:900001","synthetic-turn");
        when(service.begin(anyString(),anyString(),anyString())).thenReturn(turn);
        var recorder=new ChatTurnRecorder(service);
        recorder.begin("synthetic","u:900001","question"); recorder.append("first"); recorder.reset();
        recorder.append("partial"); recorder.mark("STOPPED"); recorder.mark("INTERRUPTED"); recorder.persist();
        verify(service).finish(eq(turn),eq("partial"),eq("STOPPED"),contains("\"version\":1"));
    }
    @Test void attachmentEvidencePersistsWithoutDuplicatingPrivateExcerptsAndRetryRemovesOldEvidence() {
        var service=mock(ConversationTurnService.class); var turn=new ConversationTurnService.Turn("synthetic","u:900001","synthetic-turn");
        when(service.begin(anyString(),anyString(),anyString())).thenReturn(turn);
        var recorder=new ChatTurnRecorder(service); recorder.begin("synthetic","u:900001","question");
        recorder.attachment("{\"text\":\"DO_NOT_DUPLICATE_PRIVATE_EXCERPT\",\"sources\":[{\"fileId\":\"synthetic-file\",\"parentId\":1}],\"warnings\":[],\"selectedSections\":1,\"totalSections\":2,\"fullCoverage\":false}");
        recorder.append("answer"); recorder.persist();
        var evidence=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service).finish(eq(turn),eq("answer"),eq("COMPLETE"),evidence.capture());
        assertTrue(evidence.getValue().contains("synthetic-file")); assertFalse(evidence.getValue().contains("DO_NOT_DUPLICATE_PRIVATE_EXCERPT"));
        recorder.reset(); recorder.append("retry"); recorder.persist();
        verify(service).finish(eq(turn),eq("retry"),eq("COMPLETE"),eq("{\"version\":1}"));
    }
}
