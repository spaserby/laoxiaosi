package com.law.backend.memory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** 请求级协调器，处理取消恰好发生在阻塞 prepare / 数据库提交期间的竞态。 */
public final class ChatTurnRecorder {
    private final ConversationTurnService service;
    private final AtomicReference<ConversationTurnService.Turn> turn = new AtomicReference<>();
    private final AtomicReference<String> terminal = new AtomicReference<>();
    private final StringBuffer answer = new StringBuffer();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private com.fasterxml.jackson.databind.node.ObjectNode evidence = JSON.createObjectNode().put("version", 1);
    public ChatTurnRecorder(ConversationTurnService service) { this.service = service; }
    public void begin(String session, String owner, String question) {
        if (terminal.get() != null) throw new CancellationException();
        turn.set(service.begin(session, owner, question));
        if (terminal.get() != null) { persist(); throw new CancellationException(); }
    }
    public void append(String text) { answer.append(text); }
    public synchronized void reset() { answer.setLength(0); evidence = JSON.createObjectNode().put("version", 1); }
    public synchronized void citation(String payload) { evidence.set("citations", parse(payload)); }
    public synchronized void attachment(String payload) {
        var input = parse(payload);
        var context = JSON.createObjectNode();
        for (String key : java.util.List.of("sources", "warnings", "selectedSections", "totalSections", "fullCoverage"))
            if (input.has(key)) context.set(key, input.get(key));
        evidence.set("attachmentContext", context);
    }
    private static com.fasterxml.jackson.databind.JsonNode parse(String payload) {
        try { return JSON.readTree(payload); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("证据事件格式无效"); }
    }
    public void mark(String status) { terminal.compareAndSet(null, status); }
    /** 同步 JDBC；只能在 boundedElastic 或 prepare 工作线程调用。重复收尾由数据库幂等处理。 */
    public synchronized void persist() { service.finish(turn.get(), answer.toString(), terminal.get() == null ? "COMPLETE" : terminal.get(), evidence.toString()); }
}
