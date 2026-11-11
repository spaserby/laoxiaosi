package com.law.backend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 短信验证码服务
 * <p>
 * <b>通道</b>：{@link SmsSender} 双实现——MockSmsSender（默认，不投递、不记录验证码）与
 * AliyunSmsSender（sms-provider=aliyun，官方 SDK）；验证码生命周期与限流逻辑与通道无关。
 * <p>
 * <b>安全设计</b>（验证码是爆破重灾区，三道闸全上）：
 * <ul>
 *   <li>重发限流和每日上限：由 SmsSendLimiter 原子预占同手机号的带 hash tag 键</li>
 *   <li>防爆破：连续错 5 次验证码作废（sms:fail:{phone}），必须重新获取</li>
 * </ul>
 * 验证码本体 5 分钟 TTL，验证成功立即删除（一次性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class SmsService {

    private static final String CODE_PREFIX = "sms:code:";
    private static final String FAIL_PREFIX = "sms:fail:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedissonClient redissonClient;
    private final AuthProperties properties;
    private final SmsSender smsSender;
    private final SmsSendLimiter limiter;

    /**
     * 发送验证码（通道由 sms-provider 决定；发送失败回滚验证码桶并抛异常）
     *
     * @return devCode（仅 mock 通道且 sms-mock-echo=true 时非空；真实通道恒为 null）
     * @throws IllegalStateException 触发限流（重发间隔/每日上限）或通道发送失败
     */
    public String sendCode(String phone) {
        if (phone == null || !AuthService.PHONE_PATTERN.matcher(phone).matches()) throw new IllegalArgumentException("手机号格式不正确");
        var reservation = limiter.reserve(phone);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        // 外部核验通道（阿里云个人版）验证码由阿里云生成，本地不存码
        boolean external = smsSender.externalVerify();
        RBucket<String> codeBucket = redissonClient.getBucket(CODE_PREFIX + phone);
        // 通道发送（mock 写日志 / aliyun 调 SDK，外部通道忽略 code）；失败回滚验证码桶避免脏码
        try {
            if (!external) codeBucket.set(code, Duration.ofSeconds(properties.getSmsCodeTtlSeconds()));
            redissonClient.getBucket(FAIL_PREFIX + phone).delete();
            smsSender.send(phone, code);
        } catch (RuntimeException e) {
            try { if (!external) codeBucket.compareAndSet(code, null); }
            finally { limiter.rollback(reservation); }
            throw e;
        }
        // 通道已接受请求后，即使清理临时租约失败，也不能释放额度允许重复发送。
        try { limiter.commit(reservation); }
        catch (RuntimeException e) { log.warn("短信预占记录等待过期清理: type={}", e.getClass().getSimpleName()); }
        boolean mockEcho = properties.isSmsMockEcho() && smsSender instanceof MockSmsSender;
        return mockEcho ? code : null;
    }

    /**
     * 校验验证码（一次性：成功即删；闸③连续错 5 次作废防爆破）
     * <p>外部核验通道（阿里云个人版）走 CheckSmsVerifyCode，本地防爆破闸仍然叠加
     */
    public boolean verify(String phone, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        RBucket<String> failBucket = redissonClient.getBucket(FAIL_PREFIX + phone);
        Object failObj = failBucket.get();
        int fails = failObj == null ? 0 : Integer.parseInt(failObj.toString());
        if (fails >= properties.getSmsMaxVerifyFails()) {
            log.warn("验证码错误次数超限，作废: phone={}", com.law.backend.util.PrivacyLog.phone(phone));
            redissonClient.getBucket(CODE_PREFIX + phone).delete();
            return false;
        }
        if (smsSender.externalVerify()) {
            boolean ok = smsSender.verifyExternally(phone, code);
            if (ok) {
                failBucket.delete();
            } else {
                failBucket.set(String.valueOf(fails + 1), Duration.ofSeconds(properties.getSmsCodeTtlSeconds()));
            }
            return ok;
        }
        RBucket<String> codeBucket = redissonClient.getBucket(CODE_PREFIX + phone);
        Object stored = codeBucket.get();
        if (stored != null && stored.toString().equals(code)) {
            codeBucket.delete();      // 一次性：验证成功立即作废
            failBucket.delete();
            return true;
        }
        failBucket.set(String.valueOf(fails + 1), Duration.ofSeconds(properties.getSmsCodeTtlSeconds()));
        return false;
    }
}
