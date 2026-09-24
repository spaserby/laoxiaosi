package com.law.backend.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具预算金标
 */
class ToolBudgetTest {

    @Test
    @DisplayName("基础预算：超限拒绝并计数 denials")
    void denyAfterLimit() {
        ToolBudget b = new ToolBudget(2);
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        assertEquals(3, b.usedCount());
        assertEquals(2, b.maxCalls());
        assertEquals(1, b.denialCount());
    }

    @Test
    @DisplayName("grant 追加预算后恢复可用（审校重试场景；被拒请求也占 used，可用 = max - used）")
    void grantExtendsLimit() {
        ToolBudget b = new ToolBudget(1);
        assertTrue(b.tryAcquire());      // used=1
        assertFalse(b.tryAcquire());     // used=2，被拒
        b.grant(2);                      // max=3
        assertEquals(3, b.maxCalls());
        assertTrue(b.tryAcquire());      // used=3
        assertFalse(b.tryAcquire());     // used=4 > 3
    }

    @Test
    @DisplayName("连续拒绝达到熔断阈值 HARD_DENY_AFTER")
    void hardDenyThreshold() {
        ToolBudget b = new ToolBudget(0);
        for (int i = 0; i < ToolBudget.HARD_DENY_AFTER; i++) {
            assertFalse(b.tryAcquire());
        }
        assertTrue(b.denialCount() >= ToolBudget.HARD_DENY_AFTER);
    }
}
