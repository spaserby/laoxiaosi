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
 * 无 token / token 无效一律静默放行为匿名——准入控制不在此处
 * （默认全接口公开；管理端接口用 @PreAuthorize 按角色收紧）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter {

    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange);
        if (token == null) {
            return chain.filter(exchange);
        }
        return jwtService.parse(token)
                .map(claims -> {
                    Long userId = Long.parseLong(claims.getSubject());
                    String role = claims.get("role", String.class);
                    Authentication auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    // Reactor Context 写入认证信息：下游 @AuthenticationPrincipal /
                    // ReactiveSecurityContextHolder 均可读取，跨线程调度不丢失
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                })
                .orElseGet(() -> chain.filter(exchange));
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
