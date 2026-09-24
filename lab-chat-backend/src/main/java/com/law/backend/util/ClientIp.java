package com.law.backend.util;

import org.springframework.web.server.ServerWebExchange;

/**
 * 客户端 IP 解析（配额分池、审计用）
 * <p>
 * <b>为什么不能直接 remote.getAddress().getHostAddress()</b>：
 * 开启 {@code server.forward-headers-strategy} 后，Spring 由 X-Forwarded-For 重建的地址是
 * {@code InetSocketAddress.createUnresolved(...)}（刻意不做 DNS 反解），
 * 其 {@code getAddress()} 返回 null → 直接取 hostAddress 会 NPE。
 * 线上曾因此让 {@code /auth/quota} 与 {@code /chat/stream} 双双 500。
 * <p>
 * {@code getHostString()} 对已解析/未解析地址都能安全取到字符串，是唯一可靠读法。
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 客户端 IP；无法确定时返回 "unknown"（配额按此分池，本方法绝不抛异常） */
    public static String of(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        String host = remote.getHostString();
        return (host == null || host.isBlank()) ? "unknown" : host;
    }
}
