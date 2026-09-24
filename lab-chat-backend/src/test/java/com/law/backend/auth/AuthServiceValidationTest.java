package com.law.backend.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证校验金标：密码强度/手机号/用户名格式纯函数验证
 * <p>
 * 校验规则全部是 static 纯函数（不依赖 Spring 容器/Redis/MySQL），单测直接锁死；
 * 注册/登录的集成链路（验证码/唯一性/BCrypt 匹配）由 e2e 与手动验证覆盖。
 */
class AuthServiceValidationTest {

    @Test
    @DisplayName("密码强度：8~32 位且必须同时含字母和数字")
    void passwordStrength() {
        assertDoesNotThrow(() -> AuthService.validatePassword("abcd1234"));
        assertDoesNotThrow(() -> AuthService.validatePassword("Passw0rd!@#"));
        // 纯数字 / 纯字母 / 过短 / 含空白 全部拒绝
        assertThrows(IllegalArgumentException.class, () -> AuthService.validatePassword("12345678"));
        assertThrows(IllegalArgumentException.class, () -> AuthService.validatePassword("abcdefgh"));
        assertThrows(IllegalArgumentException.class, () -> AuthService.validatePassword("ab12"));
        assertThrows(IllegalArgumentException.class, () -> AuthService.validatePassword("abcd 1234"));
        assertThrows(IllegalArgumentException.class, () -> AuthService.validatePassword(null));
    }

    @Test
    @DisplayName("手机号格式：1 开头 11 位数字")
    void phonePattern() {
        assertTrue(AuthService.PHONE_PATTERN.matcher("13812345678").matches());
        assertFalse(AuthService.PHONE_PATTERN.matcher("23812345678").matches());
        assertFalse(AuthService.PHONE_PATTERN.matcher("1381234567").matches());    // 10 位
        assertFalse(AuthService.PHONE_PATTERN.matcher("138123456789").matches());  // 12 位
        assertFalse(AuthService.PHONE_PATTERN.matcher("1381234567a").matches());
    }

    @Test
    @DisplayName("用户名格式：4~32 位字母数字下划线")
    void usernamePattern() {
        assertTrue(AuthService.USERNAME_PATTERN.matcher("zhang_san01").matches());
        assertFalse(AuthService.USERNAME_PATTERN.matcher("abc").matches());        // 过短
        assertFalse(AuthService.USERNAME_PATTERN.matcher("张三").matches());        // 非 ASCII
        assertFalse(AuthService.USERNAME_PATTERN.matcher("a b c d").matches());    // 含空格
    }

    @Test
    @DisplayName("BCrypt：同密码两次编码哈希不同（自动加盐），matches 均通过")
    void bcryptSalted() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String h1 = encoder.encode("admin123");
        String h2 = encoder.encode("admin123");
        assertNotEquals(h1, h2, "自动加盐：同密码哈希不同");
        assertTrue(encoder.matches("admin123", h1));
        assertFalse(encoder.matches("admin124", h1));
    }
}
