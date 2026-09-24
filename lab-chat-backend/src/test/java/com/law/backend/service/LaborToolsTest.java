package com.law.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 劳动法专精工具金标：社保补缴 / 二倍工资上限 / 试用期违法 / 工伤档位 / 失业金档位
 */
class LaborToolsTest {

    private final CalculatorService svc = new CalculatorService();

    @Test
    @DisplayName("社保补缴：20000 实发 / 7000 基数 / 24 个月 → 单位 49920 / 个人 24960 / 合计 74880")
    void socialBackpay() {
        var r = svc.socialInsuranceBackpay(20000, 7000, 24);
        // 月差额 13000：单位 16% = 13000×0.16×24 = 49920；个人 8% = 24960
        assertEquals("74880.00", r.value());
        assertEquals(49920.0, r.companyPart(), 0.01);
        assertEquals(24960.0, r.personalPart(), 0.01);
        assertTrue(r.summary().contains("16%"));
        // 北京示例费率估算 + 地区差异温馨提示必须上屏
        assertTrue(r.summary().contains("北京"));
        assertTrue(r.summary().contains("12333"));
        // 北京示例：单位 26.7% = 13000×0.267×24 = 83304；个人 10.5% = 32760
        assertTrue(r.summary().contains("83304.00"));
        assertTrue(r.summary().contains("32760.00"));
    }

    @Test
    @DisplayName("公积金补缴：同比例 12% → 单位/个人各 37440，合计 74880 全入个人账户")
    void fundBackpay() {
        var r = svc.fundBackpay(20000, 7000, 24, 12.0);
        assertEquals("74880.00", r.value());
        assertEquals(37440.0, r.companyPart(), 0.01);
        assertEquals(37440.0, r.personalPart(), 0.01);
        assertTrue(r.summary().contains("个人账户"));
    }

    @Test
    @DisplayName("二倍工资：未签满一年封顶 11 个月")
    void doubleWageCap() {
        var r = svc.doubleWage("2024-01-01", null, 10000);
        assertEquals("110000.00", r.value());   // 11 个月 × 10000
    }

    @Test
    @DisplayName("试用期：3 年合同约 6 个月试用期 = 违法（上限 6 个月合法？36 个月档上限 6 → 合法边界）；1 年合同约 3 个月 = 违法")
    void probationIllegal() {
        var legal = svc.probationCheck(36, 6, null, null);
        assertEquals("合规", legal.value());
        var illegal = svc.probationCheck(12, 3, null, null);   // 12 个月档上限 2
        assertEquals("存在违法", illegal.value());
        assertTrue(illegal.summary().contains("超上限"));
    }

    @Test
    @DisplayName("工伤十级 = 7 个月本人工资；失业金 6 年缴费 = 18 个月")
    void workInjuryAndUnemployment() {
        assertEquals("84000.00", svc.workInjuryGrant(10, 12000).value());
        assertEquals("18", svc.unemploymentMonths(6).value());
        assertEquals("24", svc.unemploymentMonths(15).value());
        assertEquals("0", svc.unemploymentMonths(0).value());
    }

    @Test
    @DisplayName("年休假：工龄 8 年应休 5 天全未休，月薪 12000 → 另补 200%")
    void annualLeave() {
        var r = svc.annualLeaveCompensation(8, 0, 12000);
        // 日工资 12000/21.75 = 551.72；5 天 × 2 = 10 天份 → 5517.24
        assertEquals(String.format("%.2f", 12000 / 21.75 * 5 * 2), r.value());
    }

    @Test
    @DisplayName("违法解除赔偿金 2N：3.5 年 → 4 个月 × 12000 × 2 = 96000")
    void wrongfulTermination() {
        var r = svc.wrongfulTerminationIndemnity(3.5, 12000, null);
        assertEquals("96000.00", r.value());
        assertTrue(r.summary().contains("2N"));
    }

    @Test
    @DisplayName("2N 三倍封顶：月薪 40000 > 社平 10000×3 → 基数 30000、年限封 12 → 720000")
    void wrongfulTerminationCap() {
        var r = svc.wrongfulTerminationIndemnity(20, 40000, 10000.0);
        assertEquals("720000.00", r.value());
    }

    @Test
    @DisplayName("代通知金 +1 = 一个月工资")
    void noticePay() {
        assertEquals("12000.00", svc.noticePay(12000).value());
    }
}
