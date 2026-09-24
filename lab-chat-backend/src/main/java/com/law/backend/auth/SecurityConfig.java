package com.law.backend.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Spring Security 配置
 * <p>
 * <b>踩坑记录</b>：本项目是 WebFlux 应用（starter-webflux + starter-tomcat，无 spring-webmvc），
 * 初版误用 Servlet 栈的 {@code @EnableWebSecurity + HttpSecurity}——它无条件引入
 * WebSecurityConfiguration，与 WebFlux 应用自动激活的 WebFluxSecurityConfiguration
 * 同时注册 conversionServicePostProcessor，启动报 bean 冲突。
 * 正确姿势：{@code @EnableWebFluxSecurity + ServerHttpSecurity + SecurityWebFilterChain}。
 * <p>
 * 关键决策：
 * <ul>
 *   <li><b>全接口 permitAll</b>："匿名可用、登录增强"形态，JWT 只做身份识别不做准入拦截；
 *       管理端收紧改用<b>路径级</b> {@code pathMatchers("/admin/**").hasRole("ADMIN")}——
 *       WebFlux 下 @PreAuthorize 对非反应式返回值（Map 等）会立即求值，此时 ThreadLocal
 *       SecurityContext 为空（Reactive 上下文只在 Reactor Context），抛
 *       AuthenticationCredentialsNotFoundException；路径级授权在 WebFilter 层
 *       原生读 Reactor Context，无此坑</li>
 *   <li><b>天然无状态</b>：WebFlux Security 没有 HttpSession 概念，认证状态由 token 承载</li>
 *   <li><b>csrf disable</b>：无 Cookie 会话则无 CSRF 攻击面（token 走 Header/URL 参数）</li>
 *   <li><b>BCrypt 强度因子 10</b>：约 100ms/次，抗爆破与体验的平衡点</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // 管理端路径级角色闸（WebFilter 层求值，Reactive 安全上下文可靠读取）
                        .pathMatchers("/admin/**").hasRole("ADMIN")
                        .anyExchange().permitAll())
                // 身份识别过滤器挂在 AUTHENTICATION 位（早于授权判定）
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION);
        return http.build();
    }
}
