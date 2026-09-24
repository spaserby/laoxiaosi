package com.law.backend.agent;

/**
 * 协作链角色统一抽象
 * <p>
 * <b>为什么现在建这个接口</b>：协作链三步（规划→检索→审校）里，检索角色住在 tool 包
 * （SearchLawTool/WebSearchTool）、审校角色住在 service 包（AnswerReviewer），
 * 规划角色（Planner）需要一个显式归属。本接口让三个角色未来可以同构编排，
 * 也是面试叙事里"协作链"的代码锚点。
 * <p>
 * <b>骨架期约定</b>：实现类只做直通（不改变运行时行为），真实协作逻辑按需接通。
 */
public interface Agent {

    /** 角色名（日志与观测用，如 planner / retriever / reviewer） */
    String name();

    /**
     * 执行本角色在协作链中的职责：读取上下文输入、把产出写回上下文
     *
     * @param context 链上流转的共享上下文
     * @return 同一上下文实例（便于链式调用）
     */
    AgentContext execute(AgentContext context);
}
