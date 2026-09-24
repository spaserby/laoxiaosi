package com.law.backend.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 分层配额服务：Redis 计数实现"速率限流 + 周期额度"双闸
 * <p>
 * <b>计数键</b>：
 * <ul>
 *   <li>周期额度 {@code quota:{daily|monthly}:{tier}:{identity}:{bucket}}，
 *       桶 = yyyyMMdd（日）或 yyyyMM（月），TTL 覆盖一个周期后自然过期</li>
 *   <li>速率 {@code quota:rpm:{identity}:{minuteTs}}，TTL 120s</li>
 * </ul>
 * <b>identity</b>：登录用户 = {@code u:{userId}}；游客 = {@code ip:{ip}}（同 IP 共享游客池防绕过）。
 * <p>
 * <b>扣减语义</b>（大厂共识）：请求受理时预扣；流失败回滚归还；安全拒答不扣（平台责任）；
 * 扣减单位 = 用户消息条数（审校重试/ReAct 多跳不重复扣）。
 * <p>
 * <b>周期口径</b>（产品决策 2026-09）：普通用户每月 50 次、律师每月 100 次；
 * 游客仍按日（IP 池共享，短周期才防得住换 IP 绕过）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaService {

    private final RedissonClient redissonClient;
    private final QuotaProperties properties;

    /** 请求级配额上下文（tier + identity） */
    public record QuotaCtx(String tier, String identity) {
    }

    /** 拒绝原因（code 供前端区分引导策略） */
    public record Deny(String code, String message) {
    }

    /**
     * 配额状态（GET /auth/quota 返回，前端透明展示）
     * <p>period 决定前端文案（{"今日"|"本月"}剩余），前端据此显示，避免月配额被写成"今日"。
     */
    public record QuotaState(String tier, int limit, String period, int used, int remaining,
                             long resetAtEpochMs) {
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

    /** 给定周期的下一个重置点 epoch 毫秒 */
    public static long nextResetAt(QuotaProperties.Period period) {
        return period.nextResetAt();
    }

    /** 耗尽话术（给出路 + 告知重置时间；游客强引导登录） */
    public static String denyMessage(String tier, int limit, QuotaProperties.Period period) {
        String reset = "将于" + period.resetText() + "重置";
        String scope = period.label();
        return switch (tier) {
            case "guest" -> scope + "游客额度（" + limit + " 次）已用完。登录即可解锁每月 50 次；" + reset + "。";
            case "user" -> scope + "额度（" + limit + " 次）已用完，" + reset + "。会员档即将开放，敬请期待。";
            case "lawyer" -> scope + "律师额度（" + limit + " 次）已用完，" + reset + "。";
            case "member" -> scope + "会员额度（" + limit + " 次）已用完，" + reset + "。";
            default -> scope + "额度已用完，" + reset + "。";
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
        QuotaProperties.Tier tier = tierConfig(ctx.tier());
        if (tier.getLimit() < 0) {
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
        // 闸②：周期额度——increment-then-check 原子预扣，避免 check-then-act 并发超扣
        RAtomicLong counter = counter(ctx, tier);
        long after = counter.incrementAndGet();
        counter.expire(Duration.ofHours(tier.getPeriod().ttlHours()));
        if (after > tier.getLimit()) {
            counter.decrementAndGet();   // 超限回滚，保持计数精确
            return new Deny("QUOTA_EXCEEDED",
                    denyMessage(ctx.tier(), tier.getLimit(), tier.getPeriod()));
        }
        return null;
    }

    /** 流失败回滚：归还 1 次周期额度（失败请求不浪费用户配额） */
    public void rollback(QuotaCtx ctx) {
        if (!properties.isEnabled() || ctx == null) {
            return;
        }
        try {
            RAtomicLong counter = counter(ctx, tierConfig(ctx.tier()));
            long after = counter.decrementAndGet();
            if (after < 0) {
                counter.set(0);
            }
        } catch (Exception e) {
            log.warn("配额回滚失败（忽略）: {}", e.getMessage());
        }
    }

    /** 当前配额状态（前端透明展示） */
    public QuotaState state(QuotaCtx ctx) {
        QuotaProperties.Tier tier = tierConfig(ctx.tier());
        int used = 0;
        if (tier.getLimit() >= 0) {
            try {
                used = (int) Math.max(0, counter(ctx, tier).get());
            } catch (Exception e) {
                log.warn("配额读取失败（按 0 计）: {}", e.getMessage());
            }
        }
        int remaining = tier.getLimit() < 0 ? -1 : Math.max(0, tier.getLimit() - used);
        return new QuotaState(ctx.tier(), tier.getLimit(),
                tier.getPeriod().name().toLowerCase(), used, remaining,
                tier.getPeriod().nextResetAt());
    }

    /** tier 配置（未知档位回落游客） */
    private QuotaProperties.Tier tierConfig(String tierName) {
        QuotaProperties.Tier tier = properties.getTiers().get(tierName);
        return tier != null ? tier : properties.getTiers().get("guest");
    }

    /** 周期计数键：quota:{daily|monthly}:{tier}:{identity}:{bucket} */
    private RAtomicLong counter(QuotaCtx ctx, QuotaProperties.Tier tier) {
        QuotaProperties.Period period = tier.getPeriod();
        return redissonClient.getAtomicLong(
                "quota:" + period.name().toLowerCase() + ":" + ctx.tier() + ":" + ctx.identity()
                        + ":" + period.bucket());
    }
}
