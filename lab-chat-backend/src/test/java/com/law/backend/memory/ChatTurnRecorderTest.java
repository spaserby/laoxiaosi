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
        verify(service).finish(turn,"","INTERRUPTED");
    }
    @Test void retryDiscardsFirstAttemptAndStopKeepsPartialSecondAnswer() {
        var service=mock(ConversationTurnService.class);
        var turn=new ConversationTurnService.Turn("synthetic","u:900001","synthetic-turn");
        when(service.begin(anyString(),anyString(),anyString())).thenReturn(turn);
        var recorder=new ChatTurnRecorder(service);
        recorder.begin("synthetic","u:900001","question"); recorder.append("first"); recorder.reset();
        recorder.append("partial"); recorder.mark("STOPPED"); recorder.mark("INTERRUPTED"); recorder.persist();
        verify(service).finish(turn,"partial","STOPPED");
    }
}
