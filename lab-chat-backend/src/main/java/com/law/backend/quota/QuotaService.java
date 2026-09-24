package com.law.backend.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 分层配额服务：Redis 计数实现"速率限流 + 日配额"双闸
 * <p>
 * <b>计数键</b>：
 * <ul>
 *   <li>日配额 {@code quota:daily:{tier}:{identity}:{yyyyMMdd}}，TTL 48h，自然日滚动</li>
 *   <li>速率 {@code quota:rpm:{identity}:{minuteTs}}，TTL 120s</li>
 * </ul>
 * <b>identity</b>：登录用户 = {@code u:{userId}}；游客 = {@code ip:{ip}}（同 IP 共享游客池防绕过）。
 * <p>
 * <b>扣减语义</b>（大厂共识）：请求受理时预扣；流失败回滚归还；安全拒答不扣（平台责任）；
 * 扣减单位 = 用户消息条数（审校重试/ReAct 多跳不重复扣）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedissonClient redissonClient;
    private final QuotaProperties properties;

    /** 请求级配额上下文（tier + identity） */
    public record QuotaCtx(String tier, String identity) {
    }

    /** 拒绝原因（code 供前端区分引导策略） */
    public record Deny(String code, String message) {
    }

    /** 配额状态（GET /auth/quota 返回，前端透明展示） */
    public record QuotaState(String tier, int dailyLimit, int used, int remaining, long resetAtEpochMs) {
    }

    /* ---------- 纯函数部分（金标可测） ---------- */

    /** role → tier 映射（null/未知 → guest） */
    public static String tierOf(String role) {
        if (role == null) {
            return "guest";
        }
        return switch (role) {
            case "ADMIN" -> "admin";
            case "LAWYER" -> "lawyer";
            case "MEMBER" -> "member";
            case "USER" -> "user";
            default -> "guest";
        };
    }

    /** 明日 00:00 的 epoch 毫秒（自然日重置点） */
    public static long nextResetAt() {
        return LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    /** 耗尽话术（大厂标准：给出路 + 告知重置时间；游客强引导登录） */
    public static String denyMessage(String tier, int dailyLimit) {
        String reset = "剩余次数将于明日 00:00 重置";
        return switch (tier) {
            case "guest" -> "今日游客额度（" + dailyLimit + " 次）已用完。登录即可解锁每日 50 次；" + reset + "。";
            case "user" -> "今日额度（" + dailyLimit + " 次）已用完，" + reset + "。会员档即将开放，敬请期待。";
            case "lawyer" -> "今日律师额度（" + dailyLimit + " 次）已用完，" + reset + "。";
            case "member" -> "今日会员额度（" + dailyLimit + " 次）已用完，" + reset + "。";
            default -> "今日额度已用完，" + reset + "。";
        };
    }

    /* ---------- 计数部分 ---------- */

    /**
     * 预扣配额：返回 null = 放行；非 null = 拒绝（RATE_LIMITED / QUOTA_EXCEEDED）
     */
    public Deny tryAcquire(QuotaCtx ctx) {
        if (!properties.isEnabled() || ctx == null) {
            return null;
        }
        QuotaProperties.Tier tier = properties.getTiers().get(ctx.tier());
        if (tier == null) {
            tier = properties.getTiers().get("guest");
        }
        if (tier.getDaily() < 0) {
            return null;    // 不限量（admin）
        }
        // 闸①：分钟速率（防脚本突发）
        if (tier.getRpm() > 0) {
            RAtomicLong rpm = redissonClient.getAtomicLong(
                    "quota:rpm:" + ctx.identity() + ":" + (System.currentTimeMillis() / 60_000));
            if (rpm.get() >= tier.getRpm()) {
                return new Deny("RATE_LIMITED", "操作太频繁，请稍后再试。");
            }
            rpm.incrementAndGet();
            rpm.expire(Duration.ofSeconds(120));
        }
        // 闸②：日配额（自然日）——increment-then-check 原子预扣，避免 check-then-act 并发超扣
        RAtomicLong daily = dailyCounter(ctx);
        long after = daily.incrementAndGet();
        daily.expire(Duration.ofHours(48));
        if (after > tier.getDaily()) {
            daily.decrementAndGet();   // 超限回滚，保持计数精确
            return new Deny("QUOTA_EXCEEDED", denyMessage(ctx.tier(), tier.getDaily()));
        }
        return null;
    }

    /** 流失败回滚：归还 1 次日配额（失败请求不浪费用户配额） */
    public void rollback(QuotaCtx ctx) {
        if (!properties.isEnabled() || ctx == null) {
            return;
        }
        try {
            RAtomicLong daily = dailyCounter(ctx);
            long after = daily.decrementAndGet();
            if (after < 0) {
                daily.set(0);
            }
        } catch (Exception e) {
            log.warn("配额回滚失败（忽略）: {}", e.getMessage());
        }
    }

    /** 当前配额状态（前端透明展示） */
    public QuotaState state(QuotaCtx ctx) {
        QuotaProperties.Tier tier = properties.getTiers().get(ctx.tier());
        if (tier == null) {
            tier = properties.getTiers().get("guest");
        }
        int used = 0;
        if (tier.getDaily() >= 0) {
            try {
                used = (int) Math.max(0, dailyCounter(ctx).get());
            } catch (Exception e) {
                log.warn("配额读取失败（按 0 计）: {}", e.getMessage());
            }
        }
        int remaining = tier.getDaily() < 0 ? -1 : Math.max(0, tier.getDaily() - used);
        return new QuotaState(ctx.tier(), tier.getDaily(), used, remaining, nextResetAt());
    }

    private RAtomicLong dailyCounter(QuotaCtx ctx) {
        return redissonClient.getAtomicLong(
                "quota:daily:" + ctx.tier() + ":" + ctx.identity() + ":" + LocalDate.now().format(DAY_FMT));
    }
}
