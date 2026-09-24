package com.law.backend.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全层金标评估
 * <p>
 * 定位：纯 JVM 断言（不启动 Spring 上下文、不依赖外部服务），mvn test 默认执行，
 * 作为 CI 一票否决门禁——安全层是确定性规则引擎，断言必须 100% 通过。
 * 对标智慧问诊方案的"免责声明覆盖率 100%、急症召回 100%"指标。
 */
class SafetyGuardTest {

    private final SafetyGuard guard = new SafetyGuard(new SafetyProperties());

    // ---------- 关卡①：拒答拦截 ----------

    @Test
    @DisplayName("拒答：胜诉承诺类问题短路返回规范话术")
    void refusal_hit_winPromise() {
        String reply = guard.checkRefusal("我这个官司能赢吗？你帮我保证一下");
        assertNotNull(reply, "命中胜诉承诺关键词必须拒答");
        assertTrue(reply.contains("执业律师"), "拒答话术必须引导咨询律师");
    }

    @Test
    @DisplayName("拒答：诉状代写请求短路")
    void refusal_hit_writeComplaint() {
        assertNotNull(guard.checkRefusal("帮我写诉状去告公司"));
    }

    @Test
    @DisplayName("拒答：正常法律咨询放行")
    void refusal_miss_normalConsult() {
        assertNull(guard.checkRefusal("解除劳动合同的经济补偿金怎么计算"));
    }

    // ---------- 关卡②：紧急置顶 ----------

    @Test
    @DisplayName("紧急：刑事强制措施命中置顶警告")
    void urgent_hit_detention() {
        String warning = guard.urgentWarning("我弟弟昨天被拘留了怎么办");
        assertNotNull(warning, "命中刑事关键词必须置顶警告");
        assertTrue(warning.contains("110") || warning.contains("律师"), "警告须给出求助路径");
    }

    @Test
    @DisplayName("紧急：普通法律问题不触发置顶")
    void urgent_miss_normalLegal() {
        assertNull(guard.urgentWarning("合同违约了要承担什么责任"));
    }

    // ---------- 关卡④：免责声明 ----------

    @Test
    @DisplayName("免责：法律类问题强制附加免责声明")
    void disclaimer_legalQuestion() {
        assertTrue(guard.shouldAppendDisclaimer("借条的诉讼时效是多久"));
    }

    @Test
    @DisplayName("免责：闲聊不附加免责声明")
    void disclaimer_chatQuestion() {
        assertFalse(guard.shouldAppendDisclaimer("红烧肉怎么做"));
    }

    // ---------- 关卡③：高风险判定 ----------

    @Test
    @DisplayName("高风险：金额/刑期预测类问题命中")
    void highRisk_hit_prediction() {
        assertTrue(guard.isHighRisk("我这种情况能赔多少钱"));
        assertTrue(guard.isHighRisk("这个罪名一般判几年"));
    }

    @Test
    @DisplayName("高风险：法条知识类问题不命中")
    void highRisk_miss_knowledge() {
        assertFalse(guard.isHighRisk("经济补偿金的计算标准是什么"));
    }

    // ---------- 总开关 ----------

    @Test
    @DisplayName("开关：enabled=false 时全部关卡放行")
    void disabled_allPassThrough() {
        SafetyProperties props = new SafetyProperties();
        props.setEnabled(false);
        SafetyGuard off = new SafetyGuard(props);
        assertNull(off.checkRefusal("这个官司能赢吗"));
        assertNull(off.urgentWarning("我被拘留了"));
        assertFalse(off.shouldAppendDisclaimer("劳动合同纠纷"));
        assertFalse(off.isHighRisk("能赔多少钱"));
    }

    @Test
    @DisplayName("边界：null/空消息不抛异常")
    void nullSafety() {
        assertNull(guard.checkRefusal(null));
        assertNull(guard.urgentWarning(""));
        assertFalse(guard.shouldAppendDisclaimer(null));
        assertEquals(0, guard.disclaimer().isBlank() ? 1 : 0, "免责声明文案不能为空");
    }
}
