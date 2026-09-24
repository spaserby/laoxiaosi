package com.law.backend.controller;

import com.law.backend.rag.KnowledgeImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理端接口：向量库手动同步
 * <p>
 * <b>状态全落 Redis</b>（取代 JVM 内 AtomicBoolean/volatile 字段）：多实例部署时
 * 同步任务在任一实例触发，所有实例的状态查询看到同一份真相；
 * 实例崩溃时 running 闸靠 TTL 自动释放（60 分钟 watchdog），不会永久死锁。
 * <ul>
 *   <li>{@code admin:sync:running} —— Bucket 重入闸（trySet 带 TTL）</li>
 *   <li>{@code admin:sync:progress} —— Hash {processed, total}（分批续传进度）</li>
 *   <li>{@code admin:sync:state} —— Hash {lastResult, lastSyncAt}</li>
 * </ul>
 * <b>权限</b>：路径级 {@code /admin/** → hasRole('ADMIN')}（SecurityConfig）。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String KEY_RUNNING = "admin:sync:running";
    private static final String KEY_PROGRESS = "admin:sync:progress";
    private static final String KEY_STATE = "admin:sync:state";

    /** running 闸 watchdog：实例崩溃后 TTL 自动释放，防永久死锁 */
    private static final Duration RUNNING_TTL = Duration.ofMinutes(60);

    private final KnowledgeImportService importService;
    private final RedissonClient redissonClient;

    /**
     * 触发向量库增量同步（异步执行，立即返回；分批续传，中断后重点从 ledger 断点继续）
     */
    @PostMapping("/sync-vectors")
    public Map<String, Object> syncVectors() {
        var running = redissonClient.getBucket(KEY_RUNNING);
        if (!running.trySet("1", RUNNING_TTL.toMinutes(), java.util.concurrent.TimeUnit.MINUTES)) {
            return Map.of("started", false, "reason", "同步任务正在执行中，请稍后再试");
        }
        redissonClient.getMap(KEY_PROGRESS).putAll(Map.of("processed", "0", "total", "0"));
        Mono.fromRunnable(() -> {
            String error = null;
            try {
                error = importService.syncLedgerToVector();
            } catch (Exception e) {
                error = e.getMessage();
            }
            redissonClient.getMap(KEY_STATE).putAll(Map.of(
                    "lastResult", error == null ? "成功" : ("失败: " + error),
                    "lastSyncAt", String.valueOf(System.currentTimeMillis())));
            running.delete();
            log.info("管理端手动同步结束: result={}", error == null ? "成功" : error);
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
        return Map.of("started", true);
    }

    /**
     * 同步状态查询（前端轮询）：running + 分批进度 + 上次结果
     */
    @GetMapping("/sync-status")
    public Map<String, Object> syncStatus() {
        Map<String, Object> out = new HashMap<>();
        out.put("running", redissonClient.getBucket(KEY_RUNNING).isExists());
        Map<Object, Object> progress = redissonClient.getMap(KEY_PROGRESS).readAllMap();
        out.put("processed", progress.getOrDefault("processed", "0"));
        out.put("total", progress.getOrDefault("total", "0"));
        Map<Object, Object> state = redissonClient.getMap(KEY_STATE).readAllMap();
        out.put("lastResult", state.getOrDefault("lastResult", "尚未同步"));
        out.put("lastSyncAt", parseLong(String.valueOf(state.getOrDefault("lastSyncAt", "0"))));
        return out;
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
