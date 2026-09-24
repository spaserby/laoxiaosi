package com.law.backend.tool;

import com.law.backend.service.CalculatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 劳动法专精工具组
 * <p>
 * <b>拆分理由</b>：劳动法是本项目专精方向，13 个劳动工具语义内聚；通用法律工具
 * （民事时效/受理费/精确查条）另置 {@link GeneralLegalTools}，为按路线裁剪留语义边界。
 * <p>
 * <b>设计思想不变</b>：复杂规则沉淀 CalculatorService 单份维护，LLM 只提取参数；
 * 前端工具箱经 ToolController 复用同一份逻辑，永不漂移。
 * <p>
 * <b>补齐 4 个原仅前端可用的计算器（试用期/年休假/失业金/工伤）、
 * 新增 2N 赔偿金与代通知金、城市基准查询；全部计算器经 {@link ToolBasis}
 * 把法条依据登记进 CitationRegistry（审校覆盖计算型回答）。
 * <p>
 * <b>描述约定</b>：只澄清适用范围与参数口径，不写"必须调用/禁止心算"类硬约束——
 * 模型能力随版本升级，选择权留给模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(LaborStandardProperties.class)
public class LaborTools {

    private final CalculatorService calculatorService;
    private final LaborStandardProperties standardProperties;

    @Tool(description = "计算劳动合同解除或终止时的经济补偿金（N），依据《劳动合同法》第47条；不包含代通知金（+1）与违法解除赔偿金（2N）")
    public String calculateSeverance(
            @ToolParam(description = "在本单位工作年限（年），可带小数，如 3.5 表示 3 年半") double workYears,
            @ToolParam(description = "解除劳动合同前 12 个月的平均月工资（元）") double monthlySalary,
            @ToolParam(description = "本地区上年度职工月平均工资（元），用于三倍封顶判断；可先调 getCityLaborStandard 查询；不传则不做封顶", required = false) Double localAvgSalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动合同法》", "第四十七条");
        String result = calculatorService.severance(workYears, monthlySalary, localAvgSalary).text();
        log.info("Tool Calling: calculateSeverance, workYears={}, monthlySalary={}, localAvgSalary={}, result={}",
                workYears, monthlySalary, localAvgSalary, result);
        return result;
    }

    @Tool(description = "计算违法解除劳动合同的赔偿金（2N）：按经济补偿标准的二倍，依据《劳动合同法》第87、48条；与经济补偿金（N）互斥不并得")
    public String calculateWrongfulTerminationIndemnity(
            @ToolParam(description = "在本单位工作年限（年），可带小数") double workYears,
            @ToolParam(description = "解除劳动合同前 12 个月的平均月工资（元）") double monthlySalary,
            @ToolParam(description = "本地区上年度职工月平均工资（元），用于三倍封顶判断；不传则不做封顶", required = false) Double localAvgSalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动合同法》", "第八十七条");
        String result = calculatorService.wrongfulTerminationIndemnity(workYears, monthlySalary, localAvgSalary).text();
        log.info("Tool Calling: calculateWrongfulTerminationIndemnity, workYears={}, monthlySalary={}, result={}",
                workYears, monthlySalary, result);
        return result;
    }

    @Tool(description = "计算代通知金（N+1 中的 +1）：用人单位依《劳动合同法》第40条解除时额外支付的一个月工资")
    public String calculateNoticePay(
            @ToolParam(description = "劳动者上一个月的工资标准（元）") double monthlySalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动合同法》", "第四十条");
        String result = calculatorService.noticePay(monthlySalary).text();
        log.info("Tool Calling: calculateNoticePay, monthlySalary={}, result={}", monthlySalary, result);
        return result;
    }

    @Tool(description = "按《劳动法》第44条计算加班费：工作日延时 1.5 倍、休息日 2 倍、法定节假日 3 倍，时薪按月薪÷21.75÷8")
    public String calculateOvertimePay(
            @ToolParam(description = "月基本工资（元）") double baseSalary,
            @ToolParam(description = "工作日延时加班小时数") double weekdayHours,
            @ToolParam(description = "休息日加班小时数（已安排补休的不应计入）") double weekendHours,
            @ToolParam(description = "法定节假日加班小时数") double holidayHours,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动法》", "第四十四条");
        String result = calculatorService.overtime(baseSalary, weekdayHours, weekendHours, holidayHours).text();
        log.info("Tool Calling: calculateOvertimePay, baseSalary={}, weekday={}, weekend={}, holiday={}, result={}",
                baseSalary, weekdayHours, weekendHours, holidayHours, result);
        return result;
    }

    @Tool(description = "计算未签书面劳动合同的二倍工资差额（最长 11 个月），依据《劳动合同法》第82条")
    public String calculateDoubleWage(
            @ToolParam(description = "用工之日，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "补签合同日期 yyyy-MM-dd；一直未签留空", required = false) String signDate,
            @ToolParam(description = "月工资（元）") double monthlySalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动合同法》", "第八十二条");
        try {
            String result = calculatorService.doubleWage(startDate, signDate, monthlySalary).text();
            log.info("Tool Calling: calculateDoubleWage, start={}, sign={}, result={}", startDate, signDate, result);
            return result;
        } catch (IllegalArgumentException e) {
            // 日期格式错误直接回给 LLM，让它修正参数重试（Function Calling 的参数自愈）
            return e.getMessage();
        }
    }

    @Tool(description = "检查约定试用期是否合法：期限上限按合同期限档（1/2/6 个月）、工资不低于约定 80%、违法赔偿金，依据《劳动合同法》第19、20、83条")
    public String calculateProbation(
            @ToolParam(description = "劳动合同期限（月），如 36") int contractMonths,
            @ToolParam(description = "约定的试用期（月）") int probationMonths,
            @ToolParam(description = "试用期工资（元），不传则不校验 80% 规则", required = false) Double probationSalary,
            @ToolParam(description = "转正后工资（元），不传则不校验 80% 规则", required = false) Double contractSalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动合同法》", "第十九条");
        String result = calculatorService.probationCheck(contractMonths, probationMonths, probationSalary, contractSalary).text();
        log.info("Tool Calling: calculateProbation, contractMonths={}, probationMonths={}, result={}",
                contractMonths, probationMonths, result);
        return result;
    }

    @Tool(description = "计算未休年休假补偿：应休天数按累计工龄 5/10/15 天档，未休部分另补 200%，依据《职工带薪年休假条例》第3、5条")
    public String calculateAnnualLeave(
            @ToolParam(description = "累计工龄（年），含不同单位的工作年限") double totalWorkYears,
            @ToolParam(description = "本年度已休年休假天数") int takenDays,
            @ToolParam(description = "月工资（元）") double monthlySalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《职工带薪年休假条例》", "第三条");
        String result = calculatorService.annualLeaveCompensation(totalWorkYears, takenDays, monthlySalary).text();
        log.info("Tool Calling: calculateAnnualLeave, years={}, taken={}, result={}", totalWorkYears, takenDays, result);
        return result;
    }

    @Tool(description = "计算失业保险金最长领取月数：缴费 1-5 年→12 个月、5-10 年→18 个月、10 年以上→24 个月，依据《社会保险法》第46条；月金额按当地标准")
    public String calculateUnemployment(
            @ToolParam(description = "累计缴费年限（年）") int contributionYears,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国社会保险法》", "第四十六条");
        String result = calculatorService.unemploymentMonths(contributionYears).text();
        log.info("Tool Calling: calculateUnemployment, years={}, result={}", contributionYears, result);
        return result;
    }

    @Tool(description = "计算工伤一次性伤残补助金：一级 27 个月…十级 7 个月 × 本人工资，依据《工伤保险条例》第35-37条；医疗/就业补助金按地方标准另计")
    public String calculateWorkInjury(
            @ToolParam(description = "伤残等级（1-10）") int disabilityGrade,
            @ToolParam(description = "本人月工资（元）") double monthlySalary,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《工伤保险条例》", "第三十七条");
        String result = calculatorService.workInjuryGrant(disabilityGrade, monthlySalary).text();
        log.info("Tool Calling: calculateWorkInjury, grade={}, salary={}, result={}", disabilityGrade, monthlySalary, result);
        return result;
    }

    @Tool(description = "估算养老保险欠缴补缴金额：单位 16% / 个人 8%（全国统一费率），含北京费率示例与地区差异提示，依据《社会保险法》第10、60、63条")
    public String calculateSocialInsuranceBackpay(
            @ToolParam(description = "实际月工资（元）") double actualSalary,
            @ToolParam(description = "公司实际缴存基数（元）") double contributionBase,
            @ToolParam(description = "欠缴月数") int months,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国社会保险法》", "第六十三条");
        String result = calculatorService.socialInsuranceBackpay(actualSalary, contributionBase, months).text();
        log.info("Tool Calling: calculateSocialInsuranceBackpay, actual={}, base={}, months={}, result={}",
                actualSalary, contributionBase, months, result);
        return result;
    }

    @Tool(description = "估算住房公积金欠缴补缴金额：单位与个人同比例缴存（5%-12%），两侧补缴额均入个人账户，依据《住房公积金管理条例》第18、20条")
    public String calculateFundBackpay(
            @ToolParam(description = "实际月工资（元）") double actualSalary,
            @ToolParam(description = "公司实际缴存基数（元）") double contributionBase,
            @ToolParam(description = "欠缴月数") int months,
            @ToolParam(description = "公积金缴存比例百分数（如 12 表示 12%），不传默认 12", required = false) Double fundRate,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《住房公积金管理条例》", "第二十条");
        String result = calculatorService.fundBackpay(actualSalary, contributionBase, months, fundRate).text();
        log.info("Tool Calling: calculateFundBackpay, actual={}, base={}, months={}, rate={}, result={}",
                actualSalary, contributionBase, months, fundRate, result);
        return result;
    }

    @Tool(description = "计算劳动仲裁时效截止日（1 年），拖欠劳动报酬争议存续期间不受限，依据《劳动争议调解仲裁法》第27条；一般民事纠纷的 3 年诉讼时效请用通用工具 calculateLimitation")
    public String calculateArbitrationLimitation(
            @ToolParam(description = "知道或应当知道权利受侵害之日，格式 yyyy-MM-dd") String knowDate,
            @ToolParam(description = "是否属于拖欠劳动报酬争议") boolean wageArrears,
            @ToolParam(description = "劳动关系终止日期 yyyy-MM-dd（欠薪争议必填；存续中留空）", required = false) String endDate,
            ToolContext toolContext) {
        ToolBasis.register(toolContext, "《中华人民共和国劳动争议调解仲裁法》", "第二十七条");
        try {
            String result = calculatorService.arbitrationLimitation(knowDate, wageArrears, endDate).text();
            log.info("Tool Calling: calculateArbitrationLimitation, knowDate={}, wageArrears={}, result={}",
                    knowDate, wageArrears, result);
            return result;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Tool(description = "查询城市劳动基准数据：月最低工资与上年度职工月平均工资（后者是经济补偿金三倍封顶依据）；城市未收录时返回已收录城市清单")
    public String getCityLaborStandard(
            @ToolParam(description = "城市名，如 北京 / 上海 / 深圳") String city) {
        LaborStandardProperties.CityStandard s =
                standardProperties.getCities().get(city == null ? "" : city.trim());
        if (s == null) {
            return "未收录该城市的基准数据。已收录城市：" + String.join("、", standardProperties.getCities().keySet())
                    + "。可请用户提供当地社平工资，或调用 webSearch 查询当地人社局最新公布值。";
        }
        String result = String.format("%s：月最低工资 %.0f 元；上年度职工月平均工资 %.0f 元（%s）",
                city.trim(), s.getMinWage(), s.getAvgWage(), s.getNote());
        log.info("Tool Calling: getCityLaborStandard, city={}, result={}", city, result);
        return result;
    }
}
