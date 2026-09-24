package com.law.backend.agent;

import java.util.List;

/**
 * 规划产物：一次问题拆解出的子任务列表
 * <p>
 * 骨架期恒为单子任务（= 原问题），因此下游强制检索/生成/审校全链路行为与接通前完全一致；
 * 未来 Planner 接通多意图拆解后，N 个子任务将驱动"逐子任务检索 → 合并上下文"。
 *
 * @param subtasks 子任务列表（至少一个）
 */
public record Plan(List<SubTask> subtasks) {

    /** 直通计划：单子任务 = 原问题（骨架期唯一形态） */
    public static Plan single(String question) {
        return new Plan(List.of(new SubTask(question, "original")));
    }

    /** 主检索请求：第一个子任务的 query（骨架期即原问题） */
    public String primaryQuery() {
        return subtasks.get(0).query();
    }

    /** 是否多子任务计划（骨架期恒 false） */
    public boolean isMulti() {
        return subtasks.size() > 1;
    }
}
