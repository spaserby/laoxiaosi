package com.law.backend.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 确定性安全层规则引擎
 * <p>
 * <b>设计来源</b>：借鉴智慧问诊方案的核心原则——"不让 LLM 自己看守自己"。
 * 安全检查是纯规则引擎（关键词匹配 + 文案拼接），零 LLM 调用、结果确定可审计；
 * 免责声明覆盖率、急症置顶等指标由架构保证 100%，不指望 System Prompt 软约束下模型自觉。
 * <p>
 * 对应问诊方案中"医疗分支强制汇聚安全检查 Agent"的思想，本项目用更轻的形态实现：
 * 规则引擎直接嵌入 ChatService 的请求/响应管道（学思想，不学多 Agent 形态）。
 * <p>
 * <b>四道关卡</b>（按请求生命周期排列）：
 * <pre>
 *   用户消息 → ① 拒答拦截（命中即短路，不调模型）
 *            → ② 紧急置顶（命中则警告拼在回答最前）
 *            → ③ 高风险检索落空提示
 *            → ④ 免责声明（法律类回答强制追加在末尾）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SafetyProperties.class)
public class SafetyGuard {

    private final SafetyProperties properties;

    /** ① 拒答拦截：命中返回规范话术（ChatService 直接短路，不调用模型）；未命中返回 null */
    public String checkRefusal(String userMessage) {
        if (properties.isEnabled() && hitAny(userMessage, properties.getRefusalKeywords())) {
            log.info("安全层拒答拦截: message={}", truncate(userMessage));
            return properties.getRefusalReply();
        }
        return null;
    }

    /** ② 紧急置顶：命中返回警告文案（拼在回答最前），未命中返回 null */
    public String urgentWarning(String userMessage) {
        if (properties.isEnabled() && hitAny(userMessage, properties.getUrgentKeywords())) {
            log.info("安全层紧急置顶: message={}", truncate(userMessage));
            return properties.getUrgentWarning();
        }
        return null;
    }

    /** ④ 免责声明判定：是否法律类问题（决定回答末尾是否强制追加免责声明） */
    public boolean shouldAppendDisclaimer(String userMessage) {
        return properties.isEnabled() && hitAny(userMessage, properties.getLegalKeywords());
    }

    /** 免责声明文案 */
    public String disclaimer() {
        return properties.getDisclaimer();
    }

    /** ③ 高风险预测类问题判定（强制检索落空时追加"建议咨询律师"提示） */
    public boolean isHighRisk(String userMessage) {
        return properties.isEnabled() && hitAny(userMessage, properties.getHighRiskKeywords());
    }

    /** 高风险拒答提示文案 */
    public String highRiskHint() {
        return properties.getHighRiskHint();
    }

    /** 关键词命中：任一关键词被消息包含即命中（contains 匹配，简单确定可审计） */
    private boolean hitAny(String message, List<String> keywords) {
        if (message == null || keywords == null) {
            return false;
        }
        return keywords.stream().anyMatch(message::contains);
    }

    /** 日志截断，避免长消息刷屏 */
    private String truncate(String message) {
        return message == null ? "" : (message.length() > 50 ? message.substring(0, 50) + "..." : message);
    }
}
