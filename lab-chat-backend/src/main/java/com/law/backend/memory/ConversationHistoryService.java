package com.law.backend.memory;

import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 面向用户的原文历史，完全不依赖 ChatMemory。 */
@Service
@RequiredArgsConstructor
public class ConversationHistoryService {
    private final MessageMapper messages;
    private final SummaryMapper summaries;
    private final SessionMapper sessions;
    private final SessionOwnershipService ownership;
    public record Page(List<Map<String,Object>> data, boolean hasMore, String nextCursor) {}
    public Page page(String sessionId, SessionOwnershipService.Owner owner, Long before, int requestedLimit) {
        ownership.require(sessionId, owner);
        if (before != null && before <= 0) throw new IllegalArgumentException("历史游标无效");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        var rows = messages.history(sessionId, owner.key(), before, limit + 1);
        boolean more = rows.size() > limit;
        var visible = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        Collections.reverse(visible);
        List<Map<String,Object>> data = visible.stream().map(m -> Map.<String,Object>of(
                "id", Long.toString(m.id()), "role", m.role().toLowerCase(Locale.ROOT),
                "content", Objects.requireNonNullElse(m.content(), ""), "createdAt", m.createdAt() == null ? "" : m.createdAt().toString(),
                "status", m.status(), "turnId", Objects.requireNonNullElse(m.turnId(), ""))).toList();
        return new Page(data, more, more ? Long.toString(visible.get(0).id()) : "");
    }
    @Transactional
    public void delete(String sessionId, SessionOwnershipService.Owner owner) {
        if (!owner.key().equals(messages.lockSession(sessionId))) throw new SessionAccessDeniedException();
        summaries.delete(sessionId);
        messages.deleteBySession(sessionId);
        sessions.deleteOwned(sessionId, owner.key());
    }
}
