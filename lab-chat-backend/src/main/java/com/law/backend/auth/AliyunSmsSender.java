package com.law.backend.auth;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云<b>个人版短信认证服务</b>通道
 * <p>
 * 与通用短信的关键差异：<b>验证码由阿里云生成与核验</b>——
 * <ul>
 *   <li>发送：{@code SendSmsVerifyCode}，模板参数固定 {@code {"code":"##code##"}} 占位，
 *       阿里云替换为系统生成的验证码后下发</li>
 *   <li>校验：{@code CheckSmsVerifyCode} 阿里云侧比对，本地不存码</li>
 * </ul>
 * 启用：{@code legal.ai.auth.sms-provider: aliyun}；签名/模板 CODE 用控制台
 * "短信认证参数配置"里系统赠送的值。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "legal.ai.auth", name = "sms-provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {

    /**
     * 个人版短信认证模板参数：code 为系统占位符（阿里云生成验证码替换）；
     * min 为模板第二个变量（有效期分钟数，赠送模板含 ${min}，缺传报
     * isv.INVALID_PARAMETERS 模板内容与参数不匹配），与 sms-code-ttl 300s 对齐取 5
     */
    private static final String FIXED_TEMPLATE_PARAM = "{\"code\":\"##code##\",\"min\":\"5\"}";

    private final AuthProperties properties;
    private volatile Client client;

    public AliyunSmsSender(AuthProperties properties) {
        this.properties = properties;
    }

    /** 懒加载单例 Client（双重检查锁） */
    private Client client() throws Exception {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AuthProperties.AliyunSms cfg = properties.getAliyun();
                    Config config = new Config()
                            .setAccessKeyId(cfg.getAccessKeyId())
                            .setAccessKeySecret(cfg.getAccessKeySecret())
                            .setEndpoint(cfg.getEndpoint());
                    client = new Client(config);
                }
            }
        }
        return client;
    }

    @Override
    public boolean externalVerify() {
        return true;    // 验证码归属阿里云，本地 Redis 不存码
    }

    @Override
    public void send(String phone, String code) {
        AuthProperties.AliyunSms cfg = properties.getAliyun();
        try {
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setSignName(cfg.getSignName())
                    .setTemplateCode(cfg.getTemplateCode())
                    .setTemplateParam(FIXED_TEMPLATE_PARAM);
            SendSmsVerifyCodeResponse response = client().sendSmsVerifyCode(request);
            String respCode = response.getBody() == null ? "" : response.getBody().getCode();
            if (!"OK".equals(respCode)) {
                String msg = response.getBody() == null ? "无响应体" : response.getBody().getMessage();
                throw new IllegalStateException("阿里云短信发送失败: " + respCode + " " + msg);
            }
            log.info("【阿里云短信认证】验证码已发送至 {}", phone);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("短信发送失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyExternally(String phone, String code) {
        try {
            // dypnsapi 2.0.0 字段名：setVerifyCode（非 setCode）；核验结果在 Model.VerifyResult
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setVerifyCode(code);
            CheckSmsVerifyCodeResponse response = client().checkSmsVerifyCode(request);
            var body = response.getBody();
            boolean ok = body != null && "OK".equals(body.getCode())
                    && (body.getModel() == null || "PASS".equalsIgnoreCase(body.getModel().getVerifyResult()));
            if (!ok && body != null) {
                log.warn("【阿里云短信认证】核验未通过: phone={}, resp={}, verifyResult={}", phone,
                        body.getMessage(), body.getModel() == null ? "-" : body.getModel().getVerifyResult());
            }
            return ok;
        } catch (Exception e) {
            log.warn("【阿里云短信认证】核验调用失败（按不通过处理）: {}", e.getMessage());
            return false;
        }
    }
}
