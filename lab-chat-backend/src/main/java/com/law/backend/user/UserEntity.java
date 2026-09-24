package com.law.backend.user;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户实体（用户名+密码注册登录 / 手机验证码重置 / RBAC 角色地基；
 * MyBatis POJO，表 app_user）
 * <p>
 * 安全设计：{@code passwordHash} 存 BCrypt 哈希（单向、加盐、慢哈希抗爆破），
 * 任何接口不得把该字段序列化返回（DTO 层隔离，见 AuthController）。
 */
@Getter
@Setter
public class UserEntity {

    private Long id;

    /** 用户名（登录凭证，唯一；手机号注册用户可空） */
    private String username;

    /** BCrypt 密码哈希（绝不存明文） */
    private String passwordHash;

    /** 手机号（唯一；验证码重置/未来验证码登录用） */
    private String phone;

    /** 角色：USER / LAWYER/ ADMIN */
    private String role;

    /** 0=正常 1=封禁 */
    private Integer status;

    /** 头像选择编号 1-12（对应前端静态资源 public/avatars/n.jpg；NULL=默认图标） */
    private String avatar;

    private LocalDateTime createdAt;
}
