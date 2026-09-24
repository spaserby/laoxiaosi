package com.law.backend.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Reviewer 质量门配置
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.review:
 *   enabled: true        # 质量门总开关
 *   routes: [legal]      # 哪些路线的回答需要过审（严肃路线才值得审）
 *   min-length: 30       # 回答长度下限（低于视为敷衍）
 * </pre>
 * <p>
 * <b>设计取向</b>：审校与安全层同一哲学——"不让 LLM 自己看守自己"，
 * 质量门同样是纯规则引擎（零 LLM 调用、确定可审计）。小模型复核是未来扩展点，
 * 当前不引入（C 端延迟敏感，规则层已能抓住"无依据作答"这一主要质量问题）。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.review")
public class ReviewProperties {

    /** 质量门总开关，false 时所有回答直接放行 */
    private boolean enabled = true;

    /** 需要过审的路线名单（默认仅 legal：严肃场景才值得付重试成本） */
    private List<String> routes = List.of("legal");

    /** 回答长度下限：低于此长度视为敷衍回答，审校不通过 */
    private int minLength = 30;
}
