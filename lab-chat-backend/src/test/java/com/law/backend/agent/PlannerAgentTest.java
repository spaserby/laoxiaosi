package com.law.backend.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Planner Agent 金标评估
 * <p>
 * 核心断言：单轮对话（history 空）与开关关闭时，execute 不触发任何模型调用，
 * Plan 恒为单子任务（query=原问题、category=original）——保证单轮零额外成本。
 * 多轮改写路径依赖模型调用，由 e2e/手动验证覆盖，此处不 mock。
 */
class PlannerAgentTest {

    /** 构造器三参数：properties + ModelRegistry + ModelRouteProperties（单轮路径不触达后两者，传 null） */
    private PlannerAgent planner(PlannerProperties props) {
        return new PlannerAgent(props, null, null);
    }

    @Test
    @DisplayName("单轮零调用：history 空时 Plan 为原问题直通（category=original）")
    void singleTurn_noRewrite() {
        AgentContext context = new AgentContext("s1", "公司解除劳动合同我能拿多少补偿？", List.of());
        planner(new PlannerProperties()).execute(context);
        Plan plan = context.getPlan();
        assertFalse(plan.isMulti());
        assertEquals("公司解除劳动合同我能拿多少补偿？", plan.primaryQuery());
        assertEquals("original", plan.subtasks().get(0).intentHint());
    }

    @Test
    @DisplayName("开关关闭：多轮也不改写（enabled=false 直通）")
    void disabled_noRewriteEvenMultiTurn() {
        PlannerProperties props = new PlannerProperties();
        props.setEnabled(false);
        // history 非空但开关关闭：不触达 ModelRegistry（null 安全）
        AgentContext context = new AgentContext("s1", "那第二种情况呢？",
                List.of(new org.springframework.ai.chat.messages.UserMessage("补偿金怎么算")));
        planner(props).execute(context);
        assertEquals("那第二种情况呢？", context.getPlan().primaryQuery());
    }

    @Test
    @DisplayName("execute 将 plan 写回上下文且返回同一实例")
    void execute_writesPlanToContext() {
        AgentContext context = new AgentContext("s1", "借条诉讼时效多久", List.of());
        AgentContext returned = planner(new PlannerProperties()).execute(context);
        assertSame(context, returned);
        assertEquals("借条诉讼时效多久", context.getPlan().primaryQuery());
    }

    @Test
    @DisplayName("角色名为 planner（协作链观测标识）")
    void name_isPlanner() {
        assertEquals("planner", planner(new PlannerProperties()).name());
    }

    @Test
    @DisplayName("Plan 工具方法：single/primaryQuery/isMulti 边界")
    void plan_helpers() {
        Plan single = Plan.single("q");
        assertFalse(single.isMulti());
        assertEquals("q", single.primaryQuery());
    }
}
