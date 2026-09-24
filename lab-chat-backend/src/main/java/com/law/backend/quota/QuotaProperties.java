package com.law.backend.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分层配额配置：tier → {额度, 周期, RPM}
 * <p>
 * <b>周期可配</b>（daily / monthly）：产品口径 2026-09 起为「普通用户每月 50 次、
 * 律师每月 100 次」；游客保留按日——游客是按 IP 池共享的，只有短周期才防得住换 IP 绕过。
 * <p>
 * 设计参照大厂共识：速率限流（RPM，防脚本突发）与周期额度（控成本）分离；
 * {@code limit = -1} 表示不限量（管理员独立池）；member 档预留。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.quota")
public class QuotaProperties {

    /** 配额总开关，false 时全部放行（降级态） */
    private boolean enabled = true;

    /** 层级配额表：guest/user/lawyer/member/admin */
    private Map<String, Tier> tiers = defaultTiers();

    private static Map<String, Tier> defaultTiers() {
        Map<String, Tier> map = new LinkedHashMap<>();
        map.put("guest", new Tier(5, Period.DAILY, 2));        // 游客：日 5 次（IP 池共享）
        map.put("user", new Tier(50, Period.MONTHLY, 10));     // 普通用户：每月 50 次
        map.put("lawyer", new Tier(100, Period.MONTHLY, 20));  // 律师：每月 100 次
        map.put("member", new Tier(500, Period.MONTHLY, 30));  // 预留档
        map.put("admin", new Tier(-1, Period.MONTHLY, -1));    // 不限量
        return map;
    }

    /**
     * 配额周期：决定计数键的桶、TTL 与重置点
     */
    public enum Period {
        /** 自然日 */
        DAILY(DateTimeFormatter.ofPattern("yyyyMMdd"), "今日", "明日 00:00", 48),
        /** 自然月 */
        MONTHLY(DateTimeFormatter.ofPattern("yyyyMM"), "本月", "下月 1 日 00:00", 40 * 24);

        private final DateTimeFormatter fmt;
        private final String label;
        private final String resetText;
        private final int ttlHours;

        Period(DateTimeFormatter fmt, String label, String resetText, int ttlHours) {
            this.fmt = fmt;
            this.label = label;
            this.resetText = resetText;
            this.ttlHours = ttlHours;
        }

        /** 当前周期桶（日 = yyyyMMdd，月 = yyyyMM） */
        public String bucket() {
            return LocalDate.now().format(fmt);
        }

        /** 下一个重置点 epoch 毫秒（日 → 明日 00:00；月 → 下月 1 日 00:00） */
        public long nextResetAt() {
            LocalDate now = LocalDate.now();
            LocalDate next = this == DAILY
                    ? now.plusDays(1)
                    : now.withDayOfMonth(1).plusMonths(1);
            return next.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }

        /** 周期显示前缀（今日 / 本月） */
        public String label() {
            return label;
        }

        /** 重置时间文案 */
        public String resetText() {
            return resetText;
        }

        /** 计数键 TTL（小时）：跨周期的残余计数自然过期，不长期占 Redis */
        public int ttlHours() {
            return ttlHours;
        }

        /** 配置值 → 枚举（daily/monthly，null/未知按 DAILY） */
        public static Period of(String value) {
            return "monthly".equalsIgnoreCase(value) ? MONTHLY : DAILY;
        }
    }

    @Data
    public static class Tier {
        /** 周期内额度上限；-1 = 不限 */
        private int limit;
        /** 周期（daily/monthly） */
        private Period period = Period.DAILY;
        /** 每分钟请求上限；-1 = 不限 */
        private int rpm;

        public Tier() {
        }

        public Tier(int limit, Period period, int rpm) {
            this.limit = limit;
            this.period = period == null ? Period.DAILY : period;
            this.rpm = rpm;
        }
    }
}
