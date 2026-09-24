package com.law.backend.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 身份卡：名字 / 定位 / 自我介绍模板
 * <p>
 * <b>为什么放配置</b>：自我介绍文案是运营内容（品牌话术调整不应发版）；
 * Java 默认值兜底，yml 可覆盖（legal.ai.identity.*）。
 * <p>
 * <b>为什么放 system prompt 而非记忆/知识库</b>：身份是每轮必须生效的约束——
 * system prompt 每请求完整重发，不进记忆窗口、不被裁剪/摘要污染、不赌检索召回。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.identity")
public class IdentityProperties {

    /** 品牌名 */
    private String name = "劳小司";

    /** 一句话定位（前端头版与模型自述同源；不绑单一部门法，避免显用户面窄） */
    private String tagline = "一位较真的AI法律助手";

    /** 自我介绍模板：被问身份/能力时逐字复述，保证品牌一致 */
    private String intro = "你好，我是劳小司：一位较真的AI法律助手。"
            + "我读的是现行有效的法条与司法解释，给的是能溯源引用、能核算金额的结论。";
}
