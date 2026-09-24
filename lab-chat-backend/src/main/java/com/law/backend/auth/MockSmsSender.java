package com.law.backend.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 短信通道（默认）：验证码只写日志，不产生真实短信费用
 * <p>
 * 切换真实通道：{@code legal.ai.auth.sms-provider: aliyun}
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "legal.ai.auth", name = "sms-provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.info("【Mock短信】向 {} 发送验证码（未接真实短信通道）", phone);
    }
}
