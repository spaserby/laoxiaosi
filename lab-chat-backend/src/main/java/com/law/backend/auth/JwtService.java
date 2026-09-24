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

    public JwtService(AuthProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getJwtExpireHours() * 3600_000L;
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
