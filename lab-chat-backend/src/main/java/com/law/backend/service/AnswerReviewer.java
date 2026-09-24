package com.law.backend.service;

import com.law.backend.rag.CitationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviewer 质量门
 * <p>
 * <b>规则②只用正则校验《法名》第X条的<b>格式</b>——模型编一个格式完全
 * 正确的假条文号（如《劳动合同法》第九十九条）就能顺利过门，质量门形同虚设。
 * <p>
 * <b>校验答案中出现的每个引用是否 ∈ 本轮真实命中集合
 * （{@link CitationRegistry}：强制打底检索 + searchLaw 多跳检索的命中汇总）。
 * 引用了检索结果中不存在的条文 → 判不通过，复用已有重试机制把原因反馈给模型。
 * <p>
 * <b>审校规则</b>（零 LLM 调用，与安全层同哲学）：
 * <ol>
 *   <li>拒臆测类回答（含"咨询执业律师"引导语）直接通过——安全层产出不被审校拦截</li>
 *   <li>答案含条文引用：每条必须 ∈ 命中集合（registry 为空却引用了具体条文 = 编造）</li>
 *   <li>答案无条文引用：须有联网来源标注（网址：/来源：），否则不通过</li>
 *   <li>长度下限：过短视为敷衍</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ReviewProperties.class)
public class AnswerReviewer {

    /** 法条引用模式：《中华人民共和国劳动合同法》第四十七条 / 第47条 均命中 */
    private static final Pattern CITATION =
            Pattern.compile("《([^》]{2,30}?)》\\s*(第[一二三四五六七八九十百零\\d]+条)");

    private final ReviewProperties properties;

    /** 审校结果：pass + 原因（不通过原因会拼进重试 Prompt 反馈给模型） */
    public record ReviewResult(boolean pass, String reason) {
    }

    /** 该路线的回答是否需要过审 */
    public boolean needReview(String route) {
        return properties.isEnabled() && properties.getRoutes().contains(route);
    }

    /**
     * 对完整回答做规则审校
     *
     * @param answer   模型完整回答（不含免责声明尾注）
     * @param registry 本轮真实检索命中登记器（可为 null：退化为仅格式校验）
     */
    public ReviewResult review(String answer, CitationRegistry registry) {
        if (!properties.isEnabled()) {
            return new ReviewResult(true, "质量门已关闭");
        }
        // 规则①：安全层产出的拒臆测/引导咨询类回答直接通过
        if (answer.contains("执业律师")) {
            return new ReviewResult(true, "拒臆测引导类回答");
        }
        // 规则②：答案中的每个条文引用必须真实命中过
        List<String[]> cited = extractCitations(answer);
        if (!cited.isEmpty()) {
            if (registry == null || registry.isEmpty()) {
                return new ReviewResult(false, "引用了具体条文但本轮检索无任何命中");
            }
            for (String[] c : cited) {
                if (!registry.contains(c[0], c[1])) {
                    return new ReviewResult(false,
                            "引用的" + c[0] + c[1] + "不在本轮检索结果中，疑似编造");
                }
            }
        } else if (!answer.contains("网址：") && !answer.contains("来源：")) {
            // 规则③：无条文引用时须有联网来源标注
            return new ReviewResult(false, "回答缺少法条引用或来源标注");
        }
        // 规则④：长度下限
        if (answer.length() < properties.getMinLength()) {
            return new ReviewResult(false, "回答过短");
        }
        return new ReviewResult(true, "规则审校通过");
    }

    /** 兼容旧调用的便捷重载（无引用集合，仅格式校验） */
    public ReviewResult review(String answer) {
        return review(answer, null);
    }

    /** 提取答案中全部条文引用：[lawName, articleNo] 列表 */
    private List<String[]> extractCitations(String answer) {
        List<String[]> result = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            result.add(new String[]{matcher.group(1), matcher.group(2)});
        }
        return result;
    }
}
