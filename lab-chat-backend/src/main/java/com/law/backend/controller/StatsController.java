package com.law.backend.controller;

import com.law.backend.service.HomeStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页事实栏统计
 * <p>
 * 检索池 = 底账有效条文/法律部数（实时 COUNT）；
 * 第三块指标为<b>回答审校通过率</b>（审校通过轮 / 审校总轮，衡量编造拦截质量门的真实表现）；
 * 审校样本 &lt; 10 轮时返回 -1，前端显示"积累中"（冷启动诚实态）。
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final HomeStatsService homeStatsService;

    @GetMapping("/home")
    public HomeStatsService.HomeStats home() {
        return homeStatsService.home();
    }
}
