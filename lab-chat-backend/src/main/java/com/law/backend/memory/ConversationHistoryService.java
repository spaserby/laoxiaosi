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
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
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
        List<Map<String,Object>> data = visible.stream().map(m -> {
            Map<String,Object> dto = new LinkedHashMap<>(Map.<String,Object>of(
                "id", Long.toString(m.id()), "role", m.role().toLowerCase(Locale.ROOT),
                "content", Objects.requireNonNullElse(m.content(), ""), "createdAt", m.createdAt() == null ? "" : m.createdAt().toString(),
                "status", m.status(), "turnId", Objects.requireNonNullElse(m.turnId(), "")));
            dto.put("evidenceAvailable", false);
            if ("ASSISTANT".equals(m.role())) {
                try {
                    var json = JSON.readTree(Objects.requireNonNullElse(m.evidence(), "{}"));
                    if (json != null && json.path("version").asInt() == 1) {
                        dto.put("evidenceAvailable", true);
                        if (json.path("citations").isArray()) dto.put("citations", JSON.convertValue(json.get("citations"), List.class));
                        if (json.path("attachmentContext").isObject()) dto.put("attachmentContext", JSON.convertValue(json.get("attachmentContext"), Map.class));
                    }
                } catch (java.io.IOException ignored) { /* 旧记录或无效元数据不冒充可溯源证据。 */ }
            }
            return dto;
        }).toList();
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
