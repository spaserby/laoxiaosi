package com.law.backend.auth;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 同手机号所有键共用 Redis hash tag；重发窗口与日额度同时原子预占。 */
@Component
@RequiredArgsConstructor
public class SmsSendLimiter {
    private final RedissonClient redis;
    private final AuthProperties properties;
    public record Reservation(List<Object> keys, String token) {}
    private static final String RESERVE = """
        if redis.call('exists',KEYS[1])==1 then return -1 end
        if tonumber(redis.call('get',KEYS[2]) or '0')>=tonumber(ARGV[2]) then return -2 end
        redis.call('set',KEYS[1],ARGV[3],'EX',ARGV[1])
        local count=redis.call('incr',KEYS[2])
        if count==1 then redis.call('expire',KEYS[2],86400) end
        redis.call('set',KEYS[3],ARGV[3],'EX',86400)
        return 1
        """;
    private static final String ROLLBACK = """
        if redis.call('get',KEYS[3])~=ARGV[1] then return 0 end
        redis.call('del',KEYS[3])
        if redis.call('get',KEYS[1])==ARGV[1] then redis.call('del',KEYS[1]) end
        if tonumber(redis.call('get',KEYS[2]) or '0')>0 then redis.call('decr',KEYS[2]) end
        return 1
        """;
    public Reservation reserve(String phone) {
        String base = "sms:send:{" + phone + "}:";
        String token = UUID.randomUUID().toString();
        List<Object> keys = List.of(base + "interval", base + "daily:" + LocalDate.now(), base + "reservation:" + token);
        Number result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RESERVE,
                RScript.ReturnType.INTEGER, keys, Math.max(1, properties.getSmsResendIntervalSeconds()),
                properties.getSmsDailyLimit(), token);
        if (result.intValue() == -1) throw new IllegalStateException("发送太频繁，请稍后重试");
        if (result.intValue() == -2) throw new IllegalStateException("今日验证码发送次数已达上限");
        if (result.intValue() != 1) throw new IllegalStateException("短信限额检查失败");
        return new Reservation(keys, token);
    }
    public void commit(Reservation reservation) { redis.getBucket((String) reservation.keys().get(2)).delete(); }
    public void rollback(Reservation reservation) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ROLLBACK, RScript.ReturnType.INTEGER,
                reservation.keys(), reservation.token());
    }
}
