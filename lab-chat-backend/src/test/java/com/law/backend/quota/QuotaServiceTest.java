package com.law.backend.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额纯函数金标：tier 映射 / 重置点 / 耗尽话术（Redis 计数部分由手动/集成验证）
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
    @DisplayName("重置点为明日 00:00（严格大于当前时间且小于后天 00:00）")
    void resetAtIsTomorrowMidnight() {
        long now = System.currentTimeMillis();
        long reset = QuotaService.nextResetAt();
        assertTrue(reset > now, "重置点在未来");
        assertTrue(reset - now <= 24L * 3600_000, "重置点在 24 小时内");
    }

    @Test
    @DisplayName("耗尽话术：游客含登录引导与重置时间；各档含重置时间")
    void denyMessages() {
        String guest = QuotaService.denyMessage("guest", 5);
        assertTrue(guest.contains("登录"), "游客话术含登录引导");
        assertTrue(guest.contains("明日 00:00"), "游客话术含重置时间");
        assertTrue(guest.contains("5"), "游客话术含额度数");
        assertTrue(QuotaService.denyMessage("user", 50).contains("明日 00:00"));
        assertTrue(QuotaService.denyMessage("lawyer", 200).contains("明日 00:00"));
    }
}
