package com.law.backend.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Planner 配置
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.planner:
 *   enabled: false   # 骨架期恒 false；接通多意图拆解后打开
 * </pre>
 * <p>
 * <b>骨架期双保险</b>：即使误开 enabled，{@link PlannerAgent} 的拆解实现尚未接通
 * （返回 null 回落单子任务），运行时行为依然零变化。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.planner")
public class PlannerProperties {

    /** 规划开关：默认开启（仅多轮触发，单轮零额外调用） */
    private boolean enabled = true;

    /** 改写/拆解用的廉价路线名（route-tools 同一套路线体系） */
    private String rewriteRoute = "cheap";
}
