package com.law.backend.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;   // 6.5 起从 web.server.context 迁至 core.context
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 身份识别过滤器
 * <p>
 * WebFlux 应用必须用 {@link WebFilter}——Servlet 的 Filter/OncePerRequestFilter
 * 在 Netty/Tomcat-reactive 运行时根本不会被调用。认证上下文用
 * {@link ReactiveSecurityContextHolder}（Reactor Context 传播，线程切换不丢失），
 * 下游 Controller 用 {@code @AuthenticationPrincipal} 直接取 userId。
 * <p>
 * token 来源双通道：
 * <ul>
 *   <li>标准：{@code Authorization: Bearer <token>} 请求头（axios 拦截器）</li>
 *   <li>降级：{@code ?token=<token>} URL 参数——浏览器 EventSource（SSE）
 *       <b>不支持自定义请求头</b>，聊天流接口只能走 URL 传参</li>
 * </ul>
 * 无 token 保持匿名；提供了无效、撤销或封禁凭据时返回401。
 * 每次请求按数据库当前账号状态和角色授权，管理端继续执行角色准入控制。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter {

    private final JwtService jwtService;
    private final com.law.backend.user.UserMapper users;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange);
        if (token == null) {
            return chain.filter(exchange);
        }
        var parsed = jwtService.parse(token);
        if (parsed.isEmpty()) return unauthorized(exchange);
        var claims = parsed.get();
        final long id;
        final long version;
        try {
            id = Long.parseLong(claims.getSubject());
            if (!(claims.get("av") instanceof Number number)) return unauthorized(exchange);
            version = number.longValue();
            if (id <= 0 || version < 0) return unauthorized(exchange);
        } catch (RuntimeException e) { return unauthorized(exchange); }
        // 每次鉴权读取当前权限，不接受 JWT 中已经过时的角色；JDBC 不占用 I/O 线程。
        return Mono.fromCallable(() -> users.findById(id))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(user -> Integer.valueOf(0).equals(user.getStatus()) && user.getAuthVersion() == version)
                .filter(user -> java.util.Set.of("USER", "LAWYER", "MEMBER", "ADMIN").contains(java.util.Objects.toString(user.getRole(), "")))
                .map(user -> (Authentication) new UsernamePasswordAuthenticationToken(
                        id, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))))
                .flatMap(auth -> chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                        .thenReturn(Boolean.TRUE))
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange).thenReturn(Boolean.FALSE)))
                .then();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        byte[] body = "{\"message\":\"登录已失效，请重新登录\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /** 双通道提取：Authorization 头优先，URL ?token= 兜底（SSE 场景） */
    private String extractToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        String param = exchange.getRequest().getQueryParams().getFirst("token");
        return (param == null || param.isBlank()) ? null : param;
    }
}
