package com.law.backend.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 城市劳动基准数据
 * <p>
 * 最低工资 = 底线保障参照；社平工资 = 经济补偿金三倍封顶判断依据（《劳动合同法》47 条）。
 * <p>
 * <b>数据归宿决策</b>：默认值放<b>代码</b>（领域参考数据，形态固定、年更频率、随代码评审），
 * 不放 yml（运维配置只放环境旋钮，且中文 Map 键在 yml 里需方括号转义易踩坑）；
 * yml 仍可按城市名覆盖（{@code legal.ai.labor-standards.cities."[城市]": {...}}），
 * 运维免发版改值的口子保留。
 * <p>
 * <b>口径声明</b>：内置值为示例口径，工具返回时携带 note 提示以当地人社局公布为准。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.labor-standards")
public class LaborStandardProperties {

    /** 城市名 → 基准数据（默认值见 {@link #defaultCities()}） */
    private Map<String, CityStandard> cities = defaultCities();

    /** 内置示例口径默认值（2024 口径） */
    private static Map<String, CityStandard> defaultCities() {
        Map<String, CityStandard> m = new LinkedHashMap<>();
        m.put("北京", new CityStandard(2540, 11761, "示例口径 2024，官方值以北京市人社局最新公布为准"));
        m.put("上海", new CityStandard(2740, 12307, "示例口径 2024，官方值以上海市人社局最新公布为准"));
        m.put("广州", new CityStandard(2300, 11262, "示例口径 2024，官方值以广州市人社局最新公布为准"));
        m.put("深圳", new CityStandard(2360, 10795, "示例口径 2024，官方值以深圳市人社局最新公布为准"));
        m.put("杭州", new CityStandard(2490, 10052, "示例口径 2024，官方值以杭州市人社局最新公布为准"));
        m.put("成都", new CityStandard(2100, 9032, "示例口径 2024，官方值以成都市人社局最新公布为准"));
        return m;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityStandard {
        /** 月最低工资（元） */
        private double minWage;
        /** 上年度职工月平均工资（元，经济补偿三倍封顶依据） */
        private double avgWage;
        /** 数据口径说明（年份/来源提示） */
        private String note = "";
    }
}
