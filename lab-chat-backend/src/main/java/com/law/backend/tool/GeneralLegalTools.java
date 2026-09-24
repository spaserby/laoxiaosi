package com.law.backend.tool;

import com.law.backend.rag.LawArticleEntity;
import com.law.backend.rag.LawArticleMapper;
import com.law.backend.service.CalculatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 通用法律工具组
 * <p>
 * 与劳动专精组 {@link LaborTools} 的边界：本组是跨领域通用能力（民事诉讼时效、
 * 诉讼费、精确查条），劳动专属计算全在 laborTools。
 * <p>
 * <b>向量检索对精确引用（"某法第 X 条原文"）天然弱，
 * 确定性回表保证逐字原文 + 章节属 + 版本信息，加固引用可信度链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeneralLegalTools {

    private final CalculatorService calculatorService;
    private final LawArticleMapper lawArticleMapper;

    @Tool(description = "计算一般民事纠纷诉讼时效届满日（3 年）并判断是否已过期，依据《民法典》第188条；劳动争议的仲裁时效（1 年）请用劳动工具 calculateArbitrationLimitation")
    public String calculateLimitation(
            @ToolParam(description = "权利受到侵害之日或权利人知道权利受侵害之日，格式 yyyy-MM-dd") String eventDate,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国民法典》", "第一百八十八条");
        try {
            String result = calculatorService.limitation(eventDate).text();
            log.info("Tool Calling: calculateLimitation, eventDate={}, result={}", eventDate, result);
            return result;
        } catch (IllegalArgumentException e) {
            // 日期格式错误直接回给 LLM，让它修正参数重试（Function Calling 的参数自愈）
            return e.getMessage();
        }
    }

    @Tool(description = "按《诉讼费用交纳办法》财产案件标准分段累计计算一审案件受理费；劳动争议案件法院受理费 10 元/件、劳动仲裁免费")
    public String calculateLitigationFee(
            @ToolParam(description = "诉讼请求的金额/标的额（元）") double claimAmount,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《诉讼费用交纳办法》", "第十三条");
        String result = calculatorService.courtFee(claimAmount).text();
        log.info("Tool Calling: calculateLitigationFee, claimAmount={}, result={}", claimAmount, result);
        return result;
    }

    @Tool(description = "按法律名称+条文编号精确查询条文原文（逐字返回，含章属与版本信息），适用于'某法第几条说了什么'；按问题语义找相关条文请用 searchLaw")
    public String getArticle(
            @ToolParam(description = "法律名称，书名号可省略，如 劳动合同法 或 《中华人民共和国劳动合同法》") String lawName,
            @ToolParam(description = "条文编号，中文数字格式，如 第四十七条") String articleNo,
            ToolContext toolContext) {
        String name = lawName == null ? "" : lawName.replace("《", "").replace("》", "").trim();
        String no = articleNo == null ? "" : articleNo.trim();
        LawArticleEntity a = lawArticleMapper.findByLawNameAndArticleNo(name, no);
        if (a == null) {
            return "未找到匹配条文。请核对法律名称与条文编号（编号形如'第四十七条'）；或改用 searchLaw 按语义检索。";
        }
        ToolBasis.register(toolContext, a.getLawName(), a.getArticleNo());
        StringBuilder sb = new StringBuilder("法律名称：").append(a.getLawName())
                .append("。条文编号：").append(a.getArticleNo()).append('\n');
        if (a.getChapterInfo() != null && !a.getChapterInfo().isBlank()) {
            sb.append("章属：").append(a.getChapterInfo()).append('\n');
        }
        if (a.getVersionInfo() != null && !a.getVersionInfo().isBlank()) {
            sb.append("版本：").append(a.getVersionInfo()).append('\n');
        }
        sb.append("条文内容：").append(a.getContent());
        log.info("Tool Calling: getArticle, law={}, no={}", name, no);
        return sb.toString();
    }
}
