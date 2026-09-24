package com.law.backend.agent;

/**
 * 子任务：Planner 拆解出的单个检索请求
 *
 * @param query      子检索关键词（骨架期 = 用户原问题）
 * @param intentHint 意图提示（如"经济补偿"/"诉讼时效"，供检索与日志使用；骨架期为 "original"）
 */
public record SubTask(String query, String intentHint) {
}
