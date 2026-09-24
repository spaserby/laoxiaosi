package com.law.backend.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户认证配置：JWT 密钥/有效期、短信 Mock 策略、验证码限流参数
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.auth")
public class AuthProperties {

    /** JWT 签名密钥（HS256 要求 ≥32 字节；生产环境务必用环境变量注入替换默认值） */
    private String jwtSecret = "legal-assistant-dev-jwt-secret-key-2026";

    /** JWT 有效期（小时），过期重新登录 */
    private long jwtExpireHours = 24;

    /** 短信 Mock 回显：true 时 /sms-code 响应带 devCode 字段（开发自测用，生产必须 false） */
    private boolean smsMockEcho = true;

    /** 验证码有效期（秒） */
    private long smsCodeTtlSeconds = 300;

    /** 验证码重发间隔（秒） */
    private long smsResendIntervalSeconds = 60;

    /** 单手机号每日验证码上限 */
    private int smsDailyLimit = 10;

    /** 验证码连续错误次数上限（超过作废需重新获取，防爆破） */
    private int smsMaxVerifyFails = 5;

    /** 短信通道：mock（默认，只写日志）| aliyun（阿里云短信 SDK） */
    private String smsProvider = "mock";

    /** 首发管理员用户名 */
    private String bootstrapAdmin = "";

    /** 阿里云短信配置（sms-provider=aliyun 时生效；yml 留占位与环境变量注入位） */
    private AliyunSms aliyun = new AliyunSms();

    @Data
    public static class AliyunSms {
        /** AccessKey ID（环境变量 ALIYUN_SMS_AK 注入） */
        private String accessKeyId = "";
        /** AccessKey Secret（环境变量 ALIYUN_SMS_SK 注入） */
        private String accessKeySecret = "";
        /** 短信签名（控制台审核通过的签名名称） */
        private String signName = "";
        /** 短信模板 CODE（个人版短信认证：控制台"短信认证参数配置"系统赠送值） */
        private String templateCode = "";
        /** （仅通用短信 dysmsapi 用；个人版 dypnsapi 模板参数固定 {"code":"##code##"}，此字段忽略） */
        private String templateParamKey = "code";
        /** 服务端点（个人版短信认证 = dypnsapi.aliyuncs.com） */
        private String endpoint = "dypnsapi.aliyuncs.com";
    }
}
