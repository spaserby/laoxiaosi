package com.law.backend.service;

import com.law.backend.rag.LawArticleMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 首页事实栏统计服务
 * <p>
 * 聚合两类真实数据源：PG 底账 COUNT（检索池规模）+ Redis 审校累计计数器
 * （回答审校通过率，取代硬编码 100%）；审校样本不足冷启动阈值时返回 -1，
 * 前端显示"积累中"（诚实态）。
 */
@Service
@RequiredArgsConstructor
public class HomeStatsService {

    /** 冷启动阈值：审校样本少于此值不展示通过率 */
    private static final long COLD_START = 10;

    private final LawArticleMapper lawArticleMapper;
    private final RedissonClient redissonClient;

    /** 首页事实栏数据（reviewPassRate = -1 表示积累中） */
    public record HomeStats(long articleCount, long lawCount, long reviewTotal, double reviewPassRate) {
    }

    public HomeStats home() {
        long reviewTotal = redissonClient.getAtomicLong("chat:stats:review_total").get();
        long reviewPass = redissonClient.getAtomicLong("chat:stats:review_pass").get();
        double rate = reviewTotal >= COLD_START
                ? Math.round(reviewPass * 1000.0 / reviewTotal) / 10.0
                : -1;
        return new HomeStats(lawArticleMapper.countByDeleted(0),
                lawArticleMapper.countDistinctLawNames(), reviewTotal, rate);
    }
}
