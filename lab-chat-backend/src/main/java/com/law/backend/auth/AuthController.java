package com.law.backend.auth;

import com.law.backend.quota.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：注册 / 登录 / 发送验证码 / 重置密码 / 当前用户
 * <p>
 * 契约：成功 200 返回业务 JSON；参数/业务校验失败统一 400 + {"message": 中文原因}
 * （前端 ElMessage 直接展示 message）。密码哈希任何接口不外泄（DTO 隔离 + 手机号脱敏）。
 */
@Slf4j
@RestController
@RequestMapping("/auth")   // 与当前 Controller 层风格一致（无 /api 前缀，vite proxy 转发）
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SmsService smsService;
    private final QuotaService quotaService;

    public record RegisterRequest(String username, String password, String phone, String code) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record SmsCodeRequest(String phone) {
    }

    public record ResetPasswordRequest(String phone, String code, String newPassword) {
    }

    public record LawyerModeRequest(boolean enabled) {
    }

    /** 头像选择请求体：编号 1-12 或空串（恢复默认） */
    public record AvatarRequest(String avatar) {
    }

    /**
     * 注册：用户名+密码+手机号+验证码（验证码验真手机号，保证重置通道可用）
     */
    @PostMapping("/register")
    public AuthService.AuthResult register(@RequestBody RegisterRequest request) {
        return authService.register(request.username(), request.password(), request.phone(), request.code());
    }

    /**
     * 登录：用户名+密码 → JWT + 用户信息
     */
    @PostMapping("/login")
    public AuthService.AuthResult login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    /**
     * 发送验证码（Mock 短信；sms-mock-echo=true 时响应回显 devCode 供开发自测，生产关闭）
     */
    @PostMapping("/sms-code")
    public Map<String, Object> sendSmsCode(@RequestBody SmsCodeRequest request) {
        String devCode = smsService.sendCode(request.phone());
        Map<String, Object> result = new HashMap<>();
        result.put("sent", true);
        result.put("devCode", devCode == null ? "" : devCode);
        return result;
    }

    /**
     * 重置密码：手机号+验证码验身份 → 新密码
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.phone(), request.code(), request.newPassword());
        return Map.of("ok", true);
    }

    /**
     * 当前用户（需登录：JwtAuthFilter 已把 userId 写进 Reactor 认证上下文，
     * @AuthenticationPrincipal 直接注入；匿名时 userId 为 null → 401）
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "未登录"));
        }
        return ResponseEntity.ok(authService.me(userId));
    }

    /**
     * 律师模式开关：需登录；开启前的免责声明确认由前端弹窗承载
     */
    @PutMapping("/lawyer-mode")
    public ResponseEntity<?> lawyerMode(@AuthenticationPrincipal Long userId,
                                        @RequestBody LawyerModeRequest request) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "未登录"));
        }
        try {
            return ResponseEntity.ok(authService.setLawyerMode(userId, request.enabled()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 头像选择：登录用户把选择编号存进 user 表，跨设备/浏览器永久生效
     */
    @PutMapping("/avatar")
    public ResponseEntity<?> avatar(@AuthenticationPrincipal Long userId,
                                    @RequestBody AvatarRequest request) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "未登录"));
        }
        try {
            return ResponseEntity.ok(authService.setAvatar(userId, request.avatar()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 当前配额状态
     */
    @GetMapping("/quota")
    public QuotaService.QuotaState quota(@AuthenticationPrincipal Long userId, ServerWebExchange exchange) {
        QuotaService.QuotaCtx ctx;
        if (userId != null) {
            try {
                String role = authService.me(userId).role();
                ctx = new QuotaService.QuotaCtx(QuotaService.tierOf(role), "u:" + userId);
            } catch (Exception e) {
                ctx = guestCtx(exchange);
            }
        } else {
            ctx = guestCtx(exchange);
        }
        return quotaService.state(ctx);
    }

    private QuotaService.QuotaCtx guestCtx(ServerWebExchange exchange) {
        // ClientIp 兼容 forward-headers 解析出的未解析地址（getAddress() 可能为 null）
        String ip = com.law.backend.util.ClientIp.of(exchange);
        return new QuotaService.QuotaCtx("guest", "ip:" + ip);
    }

    /**
     * 业务校验失败统一 400（用户名重复/密码强度/验证码错误/限流等，message 直接可展示）
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() == null ? "请求错误" : e.getMessage()));
    }
}
