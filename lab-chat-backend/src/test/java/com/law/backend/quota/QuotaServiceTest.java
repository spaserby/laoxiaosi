package com.law.backend.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额纯函数金标：tier 映射 / 周期重置点 / 耗尽话术 / 默认档位口径
 * （Redis 计数部分由手动与集成验证）
 */
class QuotaServiceTest {

    @Test
    @DisplayName("role → tier 映射：null/未知归游客，四角色各归其档")
    void tierMapping() {
        assertEquals("guest", QuotaService.tierOf(null));
        assertEquals("guest", QuotaService.tierOf("STRANGER"));
        assertEquals("user", QuotaService.tierOf("USER"));
        assertEquals("lawyer", QuotaService.tierOf("LAWYER"));
        assertEquals("member", QuotaService.tierOf("MEMBER"));
        assertEquals("admin", QuotaService.tierOf("ADMIN"));
    }

    @Test
    @DisplayName("日配额重置点为明日 00:00（在未来且不超过 24 小时）")
    void dailyResetAtIsTomorrowMidnight() {
        long now = System.currentTimeMillis();
        long reset = QuotaService.nextResetAt(QuotaProperties.Period.DAILY);
        assertTrue(reset > now, "重置点在未来");
        assertTrue(reset - now <= 24L * 3600_000, "重置点在 24 小时内");
    }

    @Test
    @DisplayName("月配额重置点为下月 1 日 00:00（在未来且不超过 32 天）")
    void monthlyResetAtIsFirstOfNextMonth() {
        long now = System.currentTimeMillis();
        long reset = QuotaService.nextResetAt(QuotaProperties.Period.MONTHLY);
        assertTrue(reset > now, "重置点在未来");
        assertTrue(reset - now <= 32L * 24 * 3600_000, "重置点在 32 天内");
        long daysToFirst = LocalDate.now().plusMonths(1).withDayOfMonth(1)
                .toEpochDay() - LocalDate.now().toEpochDay();
        assertTrue(daysToFirst <= 31, "下月 1 日距今不超过 31 天");
    }

    @Test
    @DisplayName("周期桶：日 = yyyyMMdd，月 = yyyyMM")
    void periodBuckets() {
        String today = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now());
        assertEquals(today, QuotaProperties.Period.DAILY.bucket());
        assertEquals(today.substring(0, 6), QuotaProperties.Period.MONTHLY.bucket());
    }

    @Test
    @DisplayName("耗尽话术：游客按日并含登录引导；月档说本月与下月 1 日重置")
    void denyMessages() {
        String guest = QuotaService.denyMessage("guest", 5, QuotaProperties.Period.DAILY);
        assertTrue(guest.contains("登录"), "游客话术含登录引导");
        assertTrue(guest.contains("今日"), "游客话术按日");
        assertTrue(guest.contains("5"), "游客话术含额度数");

        String user = QuotaService.denyMessage("user", 50, QuotaProperties.Period.MONTHLY);
        assertTrue(user.contains("本月"), "普通用户话术按月");
        assertTrue(user.contains("50"), "普通用户话术含额度数");
        assertTrue(user.contains("下月 1 日 00:00"), "普通用户话术含月度重置时间");

        String lawyer = QuotaService.denyMessage("lawyer", 100, QuotaProperties.Period.MONTHLY);
        assertTrue(lawyer.contains("律师"), "律师话术带档位名");
        assertTrue(lawyer.contains("100"), "律师话术含额度数");
    }

    @Test
    @DisplayName("默认档位口径：普通用户月 50、律师月 100、游客日 5")
    void defaultTierPolicy() {
        QuotaProperties props = new QuotaProperties();

        QuotaProperties.Tier user = props.getTiers().get("user");
        assertEquals(50, user.getLimit());
        assertEquals(QuotaProperties.Period.MONTHLY, user.getPeriod());

        QuotaProperties.Tier lawyer = props.getTiers().get("lawyer");
        assertEquals(100, lawyer.getLimit());
        assertEquals(QuotaProperties.Period.MONTHLY, lawyer.getPeriod());

        QuotaProperties.Tier guest = props.getTiers().get("guest");
        assertEquals(5, guest.getLimit());
        assertEquals(QuotaProperties.Period.DAILY, guest.getPeriod());
    }
}
