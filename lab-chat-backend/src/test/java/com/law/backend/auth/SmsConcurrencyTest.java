package com.law.backend.auth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式本机隔离 Redis，真实执行原子 Lua；不调用短信供应商。 */
@EnabledIfSystemProperty(named="sms.test.redis.address",matches="redis://(?:127\\.0\\.0\\.1|localhost):[0-9]+")
class SmsConcurrencyTest {
    RedissonClient redis; String phone;
    @BeforeEach void prepare() {
        var config=new Config(); config.useSingleServer().setAddress(System.getProperty("sms.test.redis.address")); redis=Redisson.create(config);
        phone="199"+String.format("%08d",java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
    }
    @AfterEach void close() {
        redis.getKeys().deleteByPattern("sms:send:{"+phone+"}:*");
        for(String key:List.of("sms:code:","sms:fail:")) redis.getBucket(key+phone).delete();
        redis.shutdown();
    }
    @Test void concurrentBurstHasSingleSendAndFailedSendReleasesReservation() throws Exception {
        var properties=new AuthProperties(); properties.setSmsDailyLimit(1); properties.setSmsResendIntervalSeconds(1);
        var sender=mock(SmsSender.class); var limiter=new SmsSendLimiter(redis,properties);
        var sms=new SmsService(redis,properties,sender,limiter);
        var executor=Executors.newFixedThreadPool(12); var gate=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<12;i++) futures.add(executor.submit(() -> { gate.await(); try { sms.sendCode(phone); return true; } catch(IllegalStateException expected) { return false; } }));
            gate.countDown(); int accepted=0; for(var f:futures) if(f.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted); verify(sender,times(1)).send(eq(phone),anyString());
            Thread.sleep(1100);
            assertThrows(IllegalStateException.class, () -> sms.sendCode(phone)); // 日额度也不能绕过
        } finally { executor.shutdownNow(); }
        properties.setSmsDailyLimit(2);
        doThrow(new IllegalStateException("synthetic delivery failure")).when(sender).send(eq(phone),anyString());
        assertThrows(IllegalStateException.class,()->sms.sendCode(phone));
        doNothing().when(sender).send(eq(phone),anyString());
        assertDoesNotThrow(()->sms.sendCode(phone)); // 失败没有消耗额度或占住重发窗口
    }
    @Test void malformedPhoneNeverCallsProvider() {
        var sender=mock(SmsSender.class); var p=new AuthProperties(); var sms=new SmsService(redis,p,sender,new SmsSendLimiter(redis,p));
        assertThrows(IllegalArgumentException.class,()->sms.sendCode("synthetic-not-a-phone")); verifyNoInteractions(sender);
    }
}
