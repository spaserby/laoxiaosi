package com.law.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与解析
 * <p>
 * HS256 对称签名；密钥来自配置（生产用环境变量注入）。解析失败（过期/篡改/格式错）
 * 统一返回 Optional.empty()，由调用方按匿名处理——学习项目全接口公开，
 * token 只做"身份识别"不做"准入拦截"。
 */
@Slf4j
@Service
@EnableConfigurationProperties(AuthProperties.class)
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;

    /** 仓库内历史上出现过的默认密钥：等同于"未配置"，绝不允许用于生产 */
    private static final String DEV_DEFAULT = "change-me-in-production-legal-assistant-dev-secret";

    /**
     * 密钥策略（P0 安全整改）：
     * <ul>
     *   <li>未配置 / 仍是仓库默认值：开发态（无 prod profile）生成本进程随机密钥——仓库里不再存在
     *       任何可被用来伪造 token 的固定密钥；代价是重启后旧 token 失效（开发可接受）</li>
     *   <li>prod profile：直接抛异常拒绝启动——生产缺强随机密钥属于部署事故，宁可起不来</li>
     * </ul>
     */
    public JwtService(AuthProperties properties, org.springframework.core.env.Environment environment) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank() || DEV_DEFAULT.equals(secret)) {
            if (java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException(
                        "生产环境必须注入强随机 JWT_SECRET（当前未配置或仍为仓库默认值），拒绝启动");
            }
            secret = randomSecret();
            log.warn("未配置 JWT_SECRET：已生成本进程随机密钥（开发可用，重启后旧 token 失效；生产请注入 JWT_SECRET）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getJwtExpireHours() * 3600_000L;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** 签发 token：subject=userId，claims 带 username/role */
    public String generate(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username == null ? "" : username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并验签；过期/篡改/非法格式一律返回 empty（调用方按匿名处理） */
    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            log.debug("JWT 解析失败（按匿名处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
