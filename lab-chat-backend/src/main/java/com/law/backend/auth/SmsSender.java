package com.law.backend.auth;

/**
 * 短信发送通道抽象
 */
public interface SmsSender {

    /**
     * 发送验证码短信；失败抛 IllegalStateException（由 AuthController 统一转 400 话术）
     * <p>外部校验通道（如阿里云个人版）忽略 code 参数（验证码由通道侧生成）
     */
    void send(String phone, String code);

    /** 验证码是否由通道侧生成与核验（阿里云个人版 dypnsapi）；false = 本地 Redis 码 */
    default boolean externalVerify() {
        return false;
    }

    /** 通道侧核验（仅 externalVerify=true 时有效） */
    default boolean verifyExternally(String phone, String code) {
        throw new UnsupportedOperationException("该通道不支持外部核验");
    }
}
