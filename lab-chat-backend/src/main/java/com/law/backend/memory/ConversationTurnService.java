package com.law.backend.memory;

import com.law.backend.service.SessionAccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.UUID;

/** 一次生成的持久化生命周期；同一会话只允许一个运行中的轮次。 */
@Service
@RequiredArgsConstructor
public class ConversationTurnService {
    private final MessageMapper messages;
    private final SummaryMapper summaries;
    public record Turn(String sessionId, String ownerKey, String turnId) {}
    @Transactional
    public Turn begin(String sessionId, String ownerKey, String question) {
        if (!ownerKey.equals(messages.lockSession(sessionId))) throw new SessionAccessDeniedException();
        if (messages.hasRunning(sessionId)) throw new IllegalArgumentException("该会话仍在生成，请等待结束或新建咨询");
        Turn turn = new Turn(sessionId, ownerKey, UUID.randomUUID().toString());
        messages.insertTurn(sessionId, "USER", question, turn.turnId(), "RUNNING");
        summaries.ensure(sessionId);
        return turn;
    }
    @Transactional
    public void finish(Turn turn, String answer, String status) {
        finish(turn, answer, status, "{}");
    }
    @Transactional
    public void finish(Turn turn, String answer, String status, String evidence) {
        if (turn == null) return;
        if (!Set.of("COMPLETE", "STOPPED", "FAILED", "INTERRUPTED").contains(status))
            throw new IllegalArgumentException("未知的轮次结束状态");
        if (messages.lockSession(turn.sessionId()) == null) return;
        if (messages.finish(turn.sessionId(), turn.turnId(), status) == 0) return;
        if (answer != null && !answer.isBlank())
            messages.insertAssistant(turn.sessionId(), answer, turn.turnId(), status, evidence);
    }
}
