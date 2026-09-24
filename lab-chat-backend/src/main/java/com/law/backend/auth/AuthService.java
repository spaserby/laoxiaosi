package com.law.backend.auth;

import com.law.backend.user.UserEntity;
import com.law.backend.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 认证服务：注册 / 登录 / 重置密码 / 当前用户
 * <p>
 * 安全设计要点：
 * <ul>
 *   <li>BCrypt 慢哈希存密码（单向 + 自动加盐 + 可调代价因子抗 GPU 爆破）</li>
 *   <li>登录失败统一话术"用户名或密码错误"——防止攻击者用报错差异枚举已注册账号</li>
 *   <li>注册必须验手机验证码——保证手机号真实，否则重置密码通道形同虚设</li>
 *   <li>校验规则抽为 static 纯函数，金标测试直接锁死（密码强度/手机号格式）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 密码强度：8~32 位且同时含字母和数字 */
    static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[\\S]{8,32}$");

    /** 手机号格式：1 开头 11 位数字（宽松校验，真实性由验证码保证） */
    static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    /** 用户名：4~32 位字母数字下划线 */
    static final Pattern USERNAME_PATTERN = Pattern.compile("^\\w{4,32}$");

    private final UserMapper userMapper;
    private final SmsService smsService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /** 认证结果：JWT + 用户信息（密码哈希绝不外泄） */
    public record AuthResult(String token, UserDto user) {
    }

    /** 用户信息 DTO（隔离实体，passwordHash/status 不出服务层） */
    public record UserDto(Long id, String username, String phone, String role, String avatar) {
        static UserDto of(UserEntity u) {
            return new UserDto(u.getId(), u.getUsername(), maskPhone(u.getPhone()), u.getRole(),
                    u.getAvatar() == null ? "" : u.getAvatar());
        }

        /** 手机号脱敏：138****8000（前端展示与日志安全） */
        private static String maskPhone(String phone) {
            if (phone == null || phone.length() != 11) {
                return phone;
            }
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
    }

    /**
     * 注册：用户名 + 密码 + 手机号 + 验证码（验证码验真手机号，保证重置通道可用）
     */
    public AuthResult register(String username, String password, String phone, String code) {
        if (!USERNAME_PATTERN.matcher(username == null ? "" : username).matches()) {
            throw new IllegalArgumentException("用户名需为 4~32 位字母、数字或下划线");
        }
        validatePassword(password);
        if (!PHONE_PATTERN.matcher(phone == null ? "" : phone).matches()) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        if (userMapper.countByUsername(username) > 0) {
            throw new IllegalArgumentException("用户名已被注册");
        }
        if (userMapper.countByPhone(phone) > 0) {
            throw new IllegalArgumentException("该手机号已注册，请直接登录或重置密码");
        }
        if (!smsService.verify(phone, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPhone(phone);
        user.setRole("USER");
        user.setStatus(0);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("用户注册成功: userId={}, username={}", user.getId(), username);
        return issue(user);
    }

    /**
     * 登录：用户名 + 密码 → JWT
     */
    public AuthResult login(String username, String password) {
        UserEntity user = userMapper.findByUsername(username == null ? "" : username);
        // 统一话术：不暴露"用户名不存在"与"密码错误"的差异，防账号枚举
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new IllegalArgumentException("账号已被封禁");
        }
        return issue(user);
    }

    /**
     * 重置密码：手机号 + 验证码验身份 → BCrypt 新哈希落库
     */
    public void resetPassword(String phone, String code, String newPassword) {
        if (!PHONE_PATTERN.matcher(phone == null ? "" : phone).matches()) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        validatePassword(newPassword);
        if (!smsService.verify(phone, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        UserEntity user = userMapper.findByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("该手机号未注册");
        }
        String hash = passwordEncoder.encode(newPassword);
        userMapper.updatePassword(user.getId(), hash);
        log.info("密码重置成功: userId={}", user.getId());
    }

    /**
     * 当前用户信息（JWT 里的 userId 反查库，角色变更即时生效）
     */
    public UserDto me(Long userId) {
        UserEntity user = userMapper.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return UserDto.of(user);
    }

    /**
     * 律师模式开关
     * <p>
     * 开启前置免责声明确认由前端弹窗承载；后端只负责 role 持久化（LAWYER/USER 互切换）。
     * 管理员账号不参与律师模式（前端禁用开关）。
     */
    public UserDto setLawyerMode(Long userId, boolean enabled) {
        UserEntity user = userMapper.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("ADMIN".equals(user.getRole())) {
            throw new IllegalArgumentException("管理员账号不参与律师模式");
        }
        user.setRole(enabled ? "LAWYER" : "USER");
        userMapper.updateRole(userId, user.getRole());
        log.info("律师模式切换: userId={}, enabled={}", userId, enabled);
        return UserDto.of(user);
    }

    private AuthResult issue(UserEntity user) {
        String token = jwtService.generate(user.getId(), user.getUsername(), user.getRole());
        return new AuthResult(token, UserDto.of(user));
    }

    /**
     * 首发管理员提权
     *
     * @return null = 目标用户不存在；true = 已提权；false = 本就是 ADMIN（幂等）
     */
    public Boolean promoteAdmin(String username) {
        UserEntity user = userMapper.findByUsername(username);
        if (user == null) {
            return null;
        }
        if ("ADMIN".equals(user.getRole())) {
            return false;
        }
        userMapper.updateRole(user.getId(), "ADMIN");
        return true;
    }

    /**
     * 头像选择：只存编号 1-12（静态资源选择，非文件存储）；空串=恢复默认图标
     *
     * @throws IllegalArgumentException 编号越界（防越权写入任意内容）
     */
    public UserDto setAvatar(Long userId, String avatar) {
        String value = avatar == null ? "" : avatar.trim();
        if (!value.isEmpty() && !value.matches("([1-9]|1[0-2])")) {
            throw new IllegalArgumentException("头像编号无效（1-12 或空）");
        }
        UserEntity user = userMapper.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        userMapper.updateAvatar(userId, value);
        user.setAvatar(value.isEmpty() ? null : value);
        log.info("头像更新: userId={}, avatar={}", userId, value.isEmpty() ? "默认" : value);
        return UserDto.of(user);
    }

    /** 密码强度校验（static 纯函数，金标直测） */
    static void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("密码需 8~32 位且同时包含字母和数字");
        }
    }
}
