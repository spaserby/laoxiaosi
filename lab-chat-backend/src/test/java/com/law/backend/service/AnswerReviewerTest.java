package com.law.backend.service;

import com.law.backend.rag.CitationRegistry;
import com.law.backend.rag.LawCitation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reviewer 质量门金标评估
 * <p>
 * 纯规则引擎断言：审校规则的命中/放行边界 + "编造条文号必须被拦截"，进 CI 一票否决。
 */
class AnswerReviewerTest {

    private final AnswerReviewer reviewer = new AnswerReviewer(new ReviewProperties());

    /** 构造含一条真实命用的登记器：《中华人民共和国劳动合同法》第四十七条 */
    private CitationRegistry registryWithLabor47() {
        CitationRegistry registry = new CitationRegistry();
        registry.register(List.of(new LawCitation(1005L,
                "中华人民共和国劳动合同法", "第四十七条", "劳动", 0.9, "text", false)));
        return registry;
    }

    @Test
    @DisplayName("通过：引用的条文在本轮命中集合内")
    void pass_citationInRegistry() {
        CitationRegistry registry = registryWithLabor47();
        // 注意：引用格式为《中华人民共和国劳动合同法》（不含书名号内"《》"嵌套），与登记法名一致
        String answer = "根据《中华人民共和国劳动合同法》第四十七条，经济补偿按工作年限每满一年支付一个月工资，"
                + "您工作 3 年可主张 3 个月工资补偿。";
        assertTrue(reviewer.review(answer, registry).pass());
    }

    @Test
    @DisplayName("不通过：编造检索结果中不存在的条文号")
    void fail_fabricatedCitation() {
        CitationRegistry registry = registryWithLabor47();
        String answer = "根据《中华人民共和国劳动合同法》第九十九条，用人单位应当支付双倍赔偿金，"
                + "您可以据此主张权利，建议尽快申请仲裁。";
        AnswerReviewer.ReviewResult result = reviewer.review(answer, registry);
        assertFalse(result.pass());
        assertTrue(result.reason().contains("不在本轮检索结果中"));
    }

    @Test
    @DisplayName("不通过：本轮检索零命中却引用具体条文")
    void fail_citationWithoutAnyHit() {
        String answer = "根据《中华人民共和国民法典》第五百七十七条，违约方应当承担继续履行或赔偿损失等违约责任，"
                + "您的情况可以据此主张赔偿。";
        assertFalse(reviewer.review(answer, new CitationRegistry()).pass());
    }

    @Test
    @DisplayName("通过：含联网来源标注的回答（无条文引用走来源规则）")
    void pass_withWebSource() {
        String answer = "经联网核查最新政策，来源：国家法律法规数据库，网址：https://flk.npc.gov.cn/xxx，"
                + "2026 年劳动争议司法解释对补偿计算口径未作调整。";
        assertTrue(reviewer.review(answer, new CitationRegistry()).pass());
    }

    @Test
    @DisplayName("通过：拒臆测引导类回答（安全层产出不可被审校拦截）")
    void pass_lawyerHint() {
        assertTrue(reviewer.review("由于本地知识库未检索到直接依据，建议携带材料咨询执业律师获取准确意见。",
                new CitationRegistry()).pass());
    }

    @Test
    @DisplayName("不通过：无任何依据的凭记忆作答")
    void fail_noCitation() {
        String answer = "经济补偿金一般是按工作年限计算的，工作几年就赔几个月工资，具体可以和公司协商。";
        AnswerReviewer.ReviewResult result = reviewer.review(answer, new CitationRegistry());
        assertFalse(result.pass());
        assertTrue(result.reason().contains("缺少法条引用"));
    }

    @Test
    @DisplayName("不通过：有引用但过短的敷衍回答")
    void fail_tooShort() {
        CitationRegistry registry = registryWithLabor47();
        assertFalse(reviewer.review("见《中华人民共和国劳动合同法》第四十七条", registry).pass());
    }

    @Test
    @DisplayName("路线过滤：仅配置路线需要过审")
    void needReview_routesOnly() {
        assertTrue(reviewer.needReview("legal"));
        assertFalse(reviewer.needReview("cheap"));
        assertFalse(reviewer.needReview("default"));
    }

    @Test
    @DisplayName("开关：enabled=false 全部放行")
    void disabled_passAll() {
        ReviewProperties props = new ReviewProperties();
        props.setEnabled(false);
        assertTrue(new AnswerReviewer(props).review("随便什么回答都没有依据", null).pass());
    }
}
