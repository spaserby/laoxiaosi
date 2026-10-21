package com.law.backend.util;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * 阻塞调用隔离工具
 * <p>
 * <b>为什么显式隔离</b>：本项目由 Tomcat 承载 WebFlux，模型 WebClient 使用 Reactor Netty。
 * JDBC（MyBatis）、Redisson 同步 API、文件 IO 会阻塞调用线程；在入口执行会占用
 * Tomcat 工作线程，在模型响应回调中执行则可能占用 Netty event loop，拖慢其他连接。
 * <p>
 * 注意两点易错处：
 * <ul>
 *   <li>Controller 返回 {@code Mono}/{@code Flux} 不代表方法体异步——返回之前的同步
 *       语句仍然跑在当前请求线程上，应把阻塞逻辑放进 {@link Callable}</li>
 *   <li>{@code concatWith}/{@code doOnNext} 等操作符里的同步 IO 跑在上一段的发射线程上，
 *       同样需要包一层（这类位置最容易被漏掉）</li>
 * </ul>
 * <b>容量约束</b>：boundedElastic 默认 10 × CPU 线程，而 HikariCP 默认只有 10 个连接，
 * 池内线程数远大于连接数时，多出的线程只是在 getConnection() 上排队等超时。
 */
public final class Blocking {

    private Blocking() {
    }

    /**
     * 同步取值 → Mono：在弹性线程池执行，异常经 onError 传播（@ExceptionHandler 仍生效）
     */
    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步副作用 → Mono&lt;Void&gt;：用于只写不返的场景（落库、清理、计数）
     */
    public static Mono<Void> run(Runnable runnable) {
        return Mono.fromRunnable(runnable).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
