package com.law.backend.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级引用登记器
 * <p>
 * <b>解决的问题</b>：AnswerReviewer 要校验"答案里引用的条文是否真的检索命中过"，
 * 但命中集合产生在两处——ChatService 的强制打底检索、ReAct 循环里 searchLaw 的多跳检索
 * （后者发生在 Spring AI 工具调用内部，ChatService 拿不到）。本登记器经 ToolContext
 * 注入请求级实例，两处命中都 register 进来，审校时统一校验。
 * <p>
 * <b>并发安全</b>：Reactor 链与工具调用可能跨线程，内部用并发容器；
 * 实例生命周期 = 单次请求（ChatService 每轮 new 一个），天然请求隔离。
 */
public class CitationRegistry {

    /** ToolContext 中的键：ChatService 注入、SearchLawTool 读取 */
    public static final String KEY = "citationRegistry";

    /** 命中引用键集合（lawName|articleNo），真伪校验用 */
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    /** 命中引用明细（保序，SSE citation 事件与前端展示用） */
    private final List<LawCitation> citations = Collections.synchronizedList(new ArrayList<>());

    /** 登记一批检索命中（打底检索与工具多跳检索共用） */
    public void register(List<LawCitation> hits) {
        if (hits == null) {
            return;
        }
        for (LawCitation c : hits) {
            if (keys.add(c.key())) {
                citations.add(c);
            }
        }
    }

    /** 校验某引用是否在本轮真实命中集合内 */
    public boolean contains(String lawName, String articleNo) {
        return keys.contains(lawName + "|" + articleNo);
    }

    /** 本轮是否有任何命中 */
    public boolean isEmpty() {
        return citations.isEmpty();
    }

    /** 命中明细快照 */
    public List<LawCitation> all() {
        synchronized (citations) {
            return List.copyOf(citations);
        }
    }
}
