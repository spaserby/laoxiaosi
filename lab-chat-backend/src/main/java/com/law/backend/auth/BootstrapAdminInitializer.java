package com.law.backend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 首发管理员提权器：上线时不碰生产库的首发 ADMIN 方案
 * <p>
 * 用法：部署注入环境变量 {@code BOOTSTRAP_ADMIN=用户名}（该用户需先正常注册），
 * 应用启动时将其 role 提升为 ADMIN；目标用户未注册则告警跳过（不自动建号，避免弱口令种子）。
 * <p>
 * <b>安全约定</b>：提权动作写 WARN 审计日志；首发完成后应从部署配置中清除该环境变量
 * （保留也无害——幂等，但违反最小权限原则）。应急场景仍可直接
 * {@code UPDATE app_user SET role='ADMIN' WHERE username=?}。
 * <p>
 * <b>分层纪律</b>：启动组件不直连 mapper，提权逻辑收口在 {@link AuthService#promoteAdmin}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminInitializer {

    private final AuthService authService;
    private final AuthProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void promoteBootstrapAdmin() {
        String name = properties.getBootstrapAdmin();
        if (name == null || name.isBlank()) {
            return;
        }
        Boolean promoted;
        try {
            promoted = authService.promoteAdmin(name);
        } catch (Exception e) {
            // 常见于“未执行 db/init.sql 就启动”：表不存在会抛 SQL 异常。
            // 可选提权动作不应拖垮整个应用启动，但必须留下可行动的 ERROR 日志。
            log.error("【bootstrap-admin】提权失败（数据库可能尚未初始化）：请先执行 db/init.sql 再重启。原因: {}",
                    e.getMessage());
            return;
        }
        if (promoted == null) {
            log.warn("【bootstrap-admin】目标用户尚未注册，跳过提权: username={}", name);
        } else if (promoted) {
            log.warn("【bootstrap-admin】已提权: username={} → ADMIN（首发完成后请清除 BOOTSTRAP_ADMIN 配置）", name);
        }
    }
}
