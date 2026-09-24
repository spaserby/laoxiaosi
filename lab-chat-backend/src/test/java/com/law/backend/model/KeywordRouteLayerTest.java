package com.law.backend.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词路由层金标评估
 * <p>
 * 关键词层是确定性旁路，
 * 其命中行为必须 100% 可回归——词表变更后跑本测试即可发现误路由。
 */
class KeywordRouteLayerTest {

    /** 构造与 application.yml keywords 同构的测试词表 */
    private KeywordRouteLayer layer() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setKeywords(Map.of(
                "reasoning", List.of("为什么", "分析", "原理", "推理", "深入", "详细解释"),
                "cheap", List.of("你好", "闲聊", "讲个笑话"),
                "legal", List.of("法律", "法条", "法规", "合同", "劳动", "诉讼", "律师", "赔偿", "补偿")));
        return new KeywordRouteLayer(properties);
    }

    @Test
    @DisplayName("闲聊问候命中 cheap 路线")
    void route_cheap() {
        assertEquals(Optional.of("cheap"), layer().tryDecide("你好呀"));
        assertEquals(Optional.of("cheap"), layer().tryDecide("给我讲个笑话"));
    }

    @Test
    @DisplayName("法律咨询命中 legal 路线")
    void route_legal() {
        assertEquals(Optional.of("legal"), layer().tryDecide("公司解除劳动合同我能要多少补偿"));
        assertEquals(Optional.of("legal"), layer().tryDecide("合同违约了怎么赔偿"));
    }

    @Test
    @DisplayName("深度思考命中 reasoning 路线")
    void route_reasoning() {
        assertEquals(Optional.of("reasoning"), layer().tryDecide("请详细解释一下这个判决的原理"));
    }

    @Test
    @DisplayName("无关键词命中放行给语义层（不越权决策）")
    void route_passThrough() {
        assertTrue(layer().tryDecide("法国的首都是哪里").isEmpty());
        assertTrue(layer().tryDecide("红烧肉怎么做").isEmpty());
    }

    @Test
    @DisplayName("边界：null/空消息放行")
    void route_nullSafe() {
        assertTrue(layer().tryDecide(null).isEmpty());
        assertTrue(layer().tryDecide("   ").isEmpty());
    }
}
