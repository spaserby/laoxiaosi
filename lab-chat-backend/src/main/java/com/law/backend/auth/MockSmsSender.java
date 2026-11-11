package com.law.backend.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 短信通道（默认）：不投递、不记录验证码；本地调试可显式启用 mock-echo
 * <p>
 * 切换真实通道：{@code legal.ai.auth.sms-provider: aliyun}
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "legal.ai.auth", name = "sms-provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.info("模拟短信发送完成");
    }
}
