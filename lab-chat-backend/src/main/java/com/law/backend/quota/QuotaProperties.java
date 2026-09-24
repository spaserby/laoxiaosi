package com.law.backend.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分层配额配置：tier → {日配额, RPM} 映射，配置驱动、档位可扩展
 * <p>
 * 设计参照大厂共识：速率限流（RPM，防脚本突发）与日配额（控成本）分离；
 * -1 表示不限量（管理员独立池）；member 档预留（会员未上线，配置先留位）。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.quota")
public class QuotaProperties {

    /** 配额总开关，false 时全部放行（降级态） */
    private boolean enabled = true;

    /** 层级配额表：guest/user/lawyer/member/admin */
    private Map<String, Tier> tiers = defaultTiers();

    private static Map<String, Tier> defaultTiers() {
        Map<String, Tier> map = new LinkedHashMap<>();
        map.put("guest", new Tier(5, 2));        // 游客：日 5 次（用户拍板）/ 2 RPM
        map.put("user", new Tier(50, 10));
        map.put("lawyer", new Tier(200, 20));
        map.put("member", new Tier(500, 30));   // 预留档
        map.put("admin", new Tier(-1, -1));     // 不限量
        return map;
    }

    @Data
    public static class Tier {
        /** 日配额（自然日重置）；-1 = 不限 */
        private int daily;
        /** 每分钟请求上限；-1 = 不限 */
        private int rpm;

        public Tier() {
        }

        public Tier(int daily, int rpm) {
            this.daily = daily;
            this.rpm = rpm;
        }
    }
}
