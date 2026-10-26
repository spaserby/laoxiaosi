package com.law.backend.memory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** 请求级协调器，处理取消恰好发生在阻塞 prepare / 数据库提交期间的竞态。 */
public final class ChatTurnRecorder {
    private final ConversationTurnService service;
    private final AtomicReference<ConversationTurnService.Turn> turn = new AtomicReference<>();
    private final AtomicReference<String> terminal = new AtomicReference<>();
    private final StringBuffer answer = new StringBuffer();
    public ChatTurnRecorder(ConversationTurnService service) { this.service = service; }
    public void begin(String session, String owner, String question) {
        if (terminal.get() != null) throw new CancellationException();
        turn.set(service.begin(session, owner, question));
        if (terminal.get() != null) { persist(); throw new CancellationException(); }
    }
    public void append(String text) { answer.append(text); }
    public void reset() { answer.setLength(0); }
    public void mark(String status) { terminal.compareAndSet(null, status); }
    /** 同步 JDBC；只能在 boundedElastic 或 prepare 工作线程调用。重复收尾由数据库幂等处理。 */
    public void persist() { service.finish(turn.get(), answer.toString(), terminal.get() == null ? "COMPLETE" : terminal.get()); }
}
