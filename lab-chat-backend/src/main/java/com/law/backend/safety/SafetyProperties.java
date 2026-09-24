package com.law.backend.safety;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 确定性安全层配置
 * <p>
 * 配置示例：
 * <pre>
 * legal.ai.safety:
 *   enabled: true
 *   disclaimer: "以上信息仅供参考..."      # 法律回答强制附加的免责声明
 *   legal-keywords: [合同, 劳动, ...]      # 命中即视为法律问题 → 附加免责声明
 *   urgent-keywords: [拘留, 逮捕, ...]     # 命中即置顶紧急警告
 *   urgent-warning: "⚠️ 紧急提示：..."
 *   refusal-keywords: [打赢官司, 写诉状]   # 命中即拒答短路（不走模型）
 *   refusal-reply: "很抱歉，这类请求..."
 *   high-risk-keywords: [能赔多少, 判几年] # 检索落空时追加"建议咨询律师"
 *   high-risk-hint: "由于本地知识库未检索到直接依据..."
 * </pre>
 * 所有词表均可在此覆盖；安全层是纯规则引擎（零 LLM 调用），关键词命中靠 contains 匹配。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.safety")
public class SafetyProperties {

    /** 安全层总开关，false 时所有检查直接放行 */
    private boolean enabled = true;

    /** 免责声明：法律类回答强制附加（架构级保证，不依赖模型自觉；文案零 emoji，视觉强调交给前端 lucide 图标） */
    private String disclaimer = "\n\n---\n以上信息仅供参考，不构成正式法律意见。涉及具体案件与重大决策，请咨询执业律师。";

    /** 法律类关键词：命中任一即附加免责声明 */
    private List<String> legalKeywords = List.of(
            "法律", "法条", "法规", "合同", "劳动", "诉讼", "律师", "赔偿", "补偿",
            "起诉", "仲裁", "离婚", "继承", "借条", "维权", "侵权", "违约");

    /** 紧急风险关键词：命中即在回答最前置顶警告 */
    private List<String> urgentKeywords = List.of(
            "拘留", "逮捕", "刑事", "坐牢", "判刑", "自杀", "自残", "家暴", "人身安全", "被打了");

    /** 紧急置顶警告文案（零 emoji） */
    private String urgentWarning = "紧急提示：如您或他人正面临人身危险或刑事强制措施，请立即拨打 110 或尽快联系执业律师。";

    /** 拒答关键词：命中即短路返回规范话术，不调用模型 */
    private List<String> refusalKeywords = List.of(
            "打赢官司", "能赢吗", "稳赢", "包赢", "帮我写诉状", "帮我写起诉状", "代写诉状", "保证胜诉");

    /** 拒答规范话术 */
    private String refusalReply = "很抱歉，我无法对案件结果作出胜诉承诺，也不提供诉状代写服务。"
            + "诉讼结果取决于证据与具体案情，建议携带材料当面咨询执业律师。";

    /** 高风险预测类关键词：强制检索落空时追加"建议咨询律师"，避免模型臆测 */
    private List<String> highRiskKeywords = List.of(
            "能赔多少", "赔多少钱", "判几年", "判多少年", "量刑", "诉讼费多少", "能拿多少");

    /** 高风险拒答提示：检索落空 + 命中高风险关键词时追加 */
    private String highRiskHint = "由于本地知识库未检索到直接依据，对于金额、刑期等具体预测请勿采信模型推算，建议携带材料咨询执业律师获取准确意见。";
}
