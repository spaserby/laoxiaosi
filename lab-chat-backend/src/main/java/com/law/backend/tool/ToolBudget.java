package com.law.backend.tool;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮对话的工具调用预算
 * <p>
 * 早期预算只管 searchLaw 一个工具；引入联网搜索后，
 * 一轮对话里模型可能"先查本地库、再查互联网"，若各管各的预算会翻倍烧 API 费用。
 * 泛化为共享预算：searchLaw 与 webSearch 从同一个 {@link ToolBudget} 扣减，
 * route-tools.max-tool-calls 即本轮"所有检索类工具"的总调用上限。
 * <p>
 * 生命周期：ChatService 每次对话 new 一个，经 ToolContext 传入（请求级隔离，天然线程安全兜一层 AtomicInteger）。
 */
public class ToolBudget {

    /** ToolContext 中的键：ChatService 注入、各工具类读取 */
    public static final String KEY = "toolBudget";

    /** 连续拒绝达到此次数后，拒绝话术升级为强制收敛指令（熔断检索成瘾空转） */
    public static final int HARD_DENY_AFTER = 3;

    private final java.util.concurrent.atomic.AtomicInteger maxCalls;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicInteger denials = new AtomicInteger();

    public ToolBudget(int maxCalls) {
        this.maxCalls = new AtomicInteger(maxCalls);
    }

    /** 尝试占用一次额度：未超限返回 true 并计数，超限返回 false（调用方返回降级文案） */
    public boolean tryAcquire() {
        boolean ok = used.incrementAndGet() <= maxCalls.get();
        if (!ok) denials.incrementAndGet();
        return ok;
    }

    /** 动态追加预算（审校重试等系统行为触发；总上限由配置侧约束） */
    public void grant(int extra) {
        maxCalls.addAndGet(extra);
    }

    /** 连续被拒次数（≥ HARD_DENY_AFTER 时工具返回强制收敛话术） */
    public int denialCount() {
        return denials.get();
    }

    /** 已使用次数（日志用） */
    public int usedCount() {
        return used.get();
    }

    /** 总预算（日志用） */
    public int maxCalls() {
        return maxCalls.get();
    }

    /**
     * 构建携带工具预算的 ToolContext 数据（ChatService 调用，预算值来自路线差异化配置）
     */
    public static Map<String, Object> context(int maxToolCalls) {
        return Map.of(KEY, new ToolBudget(maxToolCalls));
    }
}
