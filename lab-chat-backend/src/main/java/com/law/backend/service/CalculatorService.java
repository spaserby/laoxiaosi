package com.law.backend.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 法律计算器服务（前端工程配套增量：从 tool/LegalTools 抽取计算逻辑）
 * <p>
 * <b>为什么抽取</b>：4 个计算规则原先只存在于 LegalTools（@Tool，供 LLM 调用）；
 * 前端"法律工具箱"页面需要同样的计算能力走 REST 直调。逻辑沉淀在本服务单份维护，
 * LegalTools 与 ToolController 双双委托——避免"LLM 工具一套、REST 一套"的双份逻辑漂移。
 * <p>
 * <b>返回结构</b>：{@link CalcResult} 同时携带前端展示字段（value 金额/日期、summary 过程、
 * basis 法律依据）与 LLM 工具字段（text 完整自然语言描述）。
 */
@Service
public class CalculatorService {

    /** 计算结果：value=主数值（金额两位小数/日期），summary=计算过程，basis=法律依据，text=LLM 用完整描述 */
    public record CalcResult(String value, String summary, String basis, String text,
                             Double companyPart, Double personalPart) {
        /** 兼容构造：单金额工具不传单位/个人拆分（补缴类工具专用双金额） */
        public CalcResult(String value, String summary, String basis, String text) {
            this(value, summary, basis, text, null, null);
        }
    }

    /**
     * 经济补偿金（《劳动合同法》第47条）：满一年 1 个月；剩余满半年按 1 年、不满半年 0.5 个月；
     * 月工资超当地月均 3 倍按 3 倍封顶且年限最高 12 年
     */
    public CalcResult severance(double workYears, double monthlySalary, Double localAvgSalary) {
        int fullYears = (int) workYears;
        double remainder = workYears - fullYears;
        double months = fullYears + (remainder >= 0.5 ? 1.0 : (remainder > 0 ? 0.5 : 0.0));

        double salary = monthlySalary;
        String capNote = "";
        if (localAvgSalary != null && monthlySalary > localAvgSalary * 3) {
            salary = localAvgSalary * 3;
            months = Math.min(months, 12);
            capNote = "（月工资超过当地上年度职工月均工资 3 倍，已按 3 倍封顶且年限最高按 12 年计算）";
        }

        double amount = salary * months;
        String summary = String.format("工作 %s 年折算 %s 个月 × 月工资 %.2f 元%s", workYears, months, salary, capNote);
        String basis = "《劳动合同法》第47条";
        String text = String.format("经济补偿金：%.2f 元（%s，依据%s）", amount, summary, basis);
        return new CalcResult(String.format("%.2f", amount), summary, basis, text);
    }

    /**
     * 违法解除劳动合同赔偿金（《劳动合同法》第87/48 条）：按经济补偿标准的二倍（2N）；
     * 基数 N 同 47 条封顶规则（3 倍社平 + 年限 12 年）；与 N 互斥不并得
     */
    public CalcResult wrongfulTerminationIndemnity(double workYears, double monthlySalary, Double localAvgSalary) {
        int fullYears = (int) workYears;
        double remainder = workYears - fullYears;
        double months = fullYears + (remainder >= 0.5 ? 1.0 : (remainder > 0 ? 0.5 : 0.0));

        double salary = monthlySalary;
        String capNote = "";
        if (localAvgSalary != null && monthlySalary > localAvgSalary * 3) {
            salary = localAvgSalary * 3;
            months = Math.min(months, 12);
            capNote = "（月工资超过当地上年度职工月均工资 3 倍，已按 3 倍封顶且年限最高按 12 年计算）";
        }

        double amount = salary * months * 2;
        String summary = String.format("违法解除赔偿金 2N：工作 %s 年折算 %s 个月 × 月工资 %.2f 元 × 2%s",
                workYears, months, salary, capNote);
        String basis = "《劳动合同法》第87、48条";
        String text = String.format("违法解除劳动合同赔偿金：%.2f 元（%s，依据%s）", amount, summary, basis);
        return new CalcResult(String.format("%.2f", amount), summary, basis, text);
    }

    /**
     * 代通知金（《劳动合同法》第40 条，"N+1" 中的 +1）：额外支付一个月工资
     */
    public CalcResult noticePay(double monthlySalary) {
        String summary = String.format("代通知金 = 1 × 上月工资 %.2f 元", monthlySalary);
        String basis = "《劳动合同法》第40条";
        String text = String.format("代通知金：%.2f 元（%s，依据%s）", monthlySalary, summary, basis);
        return new CalcResult(String.format("%.2f", monthlySalary), summary, basis, text);
    }

    /**
     * 加班费（《劳动法》第44条）：工作日 1.5 倍 / 休息日 2 倍 / 法定节假日 3 倍，时薪 = 月薪 ÷ 21.75 ÷ 8
     */
    public CalcResult overtime(double baseSalary, double weekdayHours, double weekendHours, double holidayHours) {
        double hourlyRate = baseSalary / 21.75 / 8;
        double weekdayPay = hourlyRate * weekdayHours * 1.5;
        double weekendPay = hourlyRate * weekendHours * 2;
        double holidayPay = hourlyRate * holidayHours * 3;
        double total = weekdayPay + weekendPay + holidayPay;

        String summary = String.format(
                "时薪 %.2f 元 = 月薪 %.2f ÷ 21.75 ÷ 8；工作日 %.1f 小时 × 1.5 = %.2f 元，休息日 %.1f 小时 × 2 = %.2f 元，法定节假日 %.1f 小时 × 3 = %.2f 元",
                hourlyRate, baseSalary, weekdayHours, weekdayPay, weekendHours, weekendPay, holidayHours, holidayPay);
        String basis = "《劳动法》第44条";
        String text = String.format("加班费合计：%.2f 元（%s，依据%s）", total, summary, basis);
        return new CalcResult(String.format("%.2f", total), summary, basis, text);
    }

    /**
     * 诉讼时效（《民法典》第188条）：3 年普通时效，自知道权利受侵害之日起算
     *
     * @throws IllegalArgumentException eventDate 非 yyyy-MM-dd 格式
     */
    public CalcResult limitation(String eventDate) {
        LocalDate start;
        try {
            start = LocalDate.parse(eventDate);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("日期格式不正确，请使用 yyyy-MM-dd 格式（如 2023-05-01）");
        }
        LocalDate deadline = start.plusYears(3);
        LocalDate today = LocalDate.now();

        String summary;
        if (deadline.isAfter(today)) {
            long remaining = ChronoUnit.DAYS.between(today, deadline);
            summary = String.format("距今还有 %d 天，尚未过期", remaining);
        } else {
            long overdue = ChronoUnit.DAYS.between(deadline, today);
            summary = String.format("已过期 %d 天，权利人可能丧失胜诉权", overdue);
        }
        String basis = "《民法典》第188条";
        String text = String.format("诉讼时效截止日为 %s，%s（依据%s：三年普通时效自知道权利受侵害之日起计算）",
                deadline, summary, basis);
        return new CalcResult(deadline.toString(), summary, basis, text);
    }

    /**
     * 案件受理费（《诉讼费用交纳办法》第13条）：财产案件按标的额分段累计，1 万内固定 50 元
     */
    public CalcResult courtFee(double claimAmount) {
        double[][] brackets = {
                {10000, 100000, 0.025},
                {100000, 200000, 0.02},
                {200000, 500000, 0.015},
                {500000, 1000000, 0.01},
                {1000000, 2000000, 0.009},
                {2000000, 5000000, 0.008},
                {5000000, 10000000, 0.007},
                {10000000, 20000000, 0.006},
                {20000000, Double.MAX_VALUE, 0.005}
        };

        double fee = 50;
        StringBuilder detail = new StringBuilder("1万元内固定 50 元");
        if (claimAmount > 10000) {
            double remaining = claimAmount - 10000;
            for (double[] b : brackets) {
                double part = Math.min(remaining, b[1] - b[0]);
                if (part <= 0) {
                    break;
                }
                double partFee = part * b[2];
                fee += partFee;
                detail.append(String.format("；%.0f-%.0f 万部分 %.2f 元", b[0] / 10000, b[1] / 10000, partFee));
                remaining -= part;
            }
        }

        String summary = String.format("标的额 %.0f 元，分段累计：%s", claimAmount, detail);
        String basis = "《诉讼费用交纳办法》第13条";
        String text = String.format("案件受理费：%.2f 元（%s，依据%s）", fee, summary, basis);
        return new CalcResult(String.format("%.2f", fee), summary, basis, text);
    }

    /* ==================== 劳动法专精工具集 ==================== */

    /**
     * 社保（养老）欠缴补缴估算：单位 16% / 个人 8% 均为全国统一费率
     * （国办发〔2019〕13 号 / 国发〔2005〕38 号）；医疗/失业/工伤费率地标差异大不纳入
     */
    public CalcResult socialInsuranceBackpay(double actualSalary, double contributionBase, int months) {
        double diff = Math.max(0, actualSalary - contributionBase);
        double company = diff * 0.16 * months;
        double personal = diff * 0.08 * months;
        double total = company + personal;
        // 北京示例费率：医疗单位 9.8%/个人 2%、失业单位与个人各 0.5%、
        // 工伤单位按行业类别 0.2%-1.9%（示例取 0.4%）个人不缴——用于估算非全国统一的三险
        double bjCompany = diff * (0.16 + 0.098 + 0.005 + 0.004) * months;
        double bjPersonal = diff * (0.08 + 0.02 + 0.005) * months;
        String summary = String.format(
                "月差额 %.2f 元（实际 %.2f − 基数 %.2f）× %d 个月：养老单位 16%% = %.2f 元，个人 8%% = %.2f 元（入个人账户），合计 %.2f 元；"
                        + "含医疗/失业/工伤按北京费率示例（单位 9.8%%+0.5%%+0.4%%，个人 2%%+0.5%%）：单位共 %.2f 元，个人共 %.2f 元。"
                        + "温馨提示：医疗/失业/工伤保险的费率与基数上下限由各统筹地区制定，城市之间存在差异，"
                        + "含这三险的金额系按北京现行费率的示例估算，仅供量级参考；"
                        + "准确补缴额请以参保地最新政策为准，或拨打 12333 咨询确认",
                diff, actualSalary, contributionBase, months, company, personal, total, bjCompany, bjPersonal);
        String basis = "《社会保险法》第10、60、63条；国办发〔2019〕13号；国发〔2005〕38号";
        String text = String.format("养老保险补缴：单位应补 %.2f 元，个人应补 %.2f 元，合计 %.2f 元（%s，依据%s）",
                company, personal, total, summary, basis);
        return new CalcResult(String.format("%.2f", total), summary, basis, text, company, personal);
    }

    /**
     * 公积金欠缴补缴估算：单位与个人同比例缴存（5%-12%，《住房公积金管理条例》第18条），
     * 补缴后两侧金额全部进入个人公积金账户（相当于双倍入账）
     */
    public CalcResult fundBackpay(double actualSalary, double contributionBase, int months, Double fundRate) {
        double rate = fundRate == null ? 0.12 : fundRate / 100.0;
        double diff = Math.max(0, actualSalary - contributionBase);
        double company = diff * rate * months;
        double personal = company;
        double total = company + personal;
        String summary = String.format(
                "月差额 %.2f 元（实际 %.2f − 基数 %.2f）× %d 个月 × 比例 %.0f%%：单位 %.2f 元，个人 %.2f 元，合计 %.2f 元"
                        + "（两侧补缴额均入你的公积金个人账户）",
                diff, actualSalary, contributionBase, months, rate * 100, company, personal, total);
        String basis = "《住房公积金管理条例》第18、19、20条";
        String text = String.format("公积金补缴：单位应补 %.2f 元，个人应补 %.2f 元，合计 %.2f 元（%s，依据%s）",
                company, personal, total, summary, basis);
        return new CalcResult(String.format("%.2f", total), summary, basis, text, company, personal);
    }

    /**
     * 劳动仲裁时效（《劳动争议调解仲裁法》第27条）：1 年；
     * 拖欠劳动报酬争议在劳动关系存续期间不受 1 年限制（离职后 1 年内主张）
     */
    public CalcResult arbitrationLimitation(String knowDate, boolean wageArrears, String endDate) {
        LocalDate know;
        try {
            know = LocalDate.parse(knowDate);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("日期格式不正确，请使用 yyyy-MM-dd 格式");
        }
        LocalDate today = LocalDate.now();
        String basis = "《劳动争议调解仲裁法》第27条";
        if (wageArrears && (endDate == null || endDate.isBlank())) {
            String summary = "拖欠劳动报酬争议且劳动关系存续中：不受 1 年仲裁时效限制，可随时主张；"
                    + "劳动关系终止的，自终止之日起 1 年内提出";
            return new CalcResult("不受限", summary, basis,
                    "劳动仲裁时效：" + summary + "（依据" + basis + "）");
        }
        LocalDate start = wageArrears ? parseDate(endDate) : know;
        LocalDate deadline = start.plusYears(1);
        String summary = deadline.isAfter(today)
                ? String.format("自 %s 起算 1 年，截止 %s，距今还有 %d 天", start, deadline, ChronoUnit.DAYS.between(today, deadline))
                : String.format("自 %s 起算 1 年，截止 %s，已过期 %d 天（可能丧失胜诉权）", start, deadline, ChronoUnit.DAYS.between(deadline, today));
        return new CalcResult(deadline.toString(), summary, basis,
                "劳动仲裁时效截止日 " + deadline + "：" + summary + "（依据" + basis + "）");
    }

    private LocalDate parseDate(String d) {
        try {
            return LocalDate.parse(d);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("日期格式不正确，请使用 yyyy-MM-dd 格式");
        }
    }

    /**
     * 未签书面合同二倍工资差额（《劳动合同法》第82条 + 实施条例第6/7条）：
     * 自用工满一个月次日起至补签前一日（未签则至满一年前一日），最长 11 个月
     */
    public CalcResult doubleWage(String startDate, String signDate, double monthlySalary) {
        LocalDate start = parseDate(startDate).plusMonths(1);   // 满一个月次日
        LocalDate end;
        if (signDate == null || signDate.isBlank()) {
            end = parseDate(startDate).plusMonths(12).minusDays(1);
        } else {
            end = parseDate(signDate).minusDays(1);
        }
        long months = ChronoUnit.MONTHS.between(start, end.plusDays(1));
        months = Math.max(0, Math.min(months, 11));
        double amount = monthlySalary * months;
        String summary = String.format("二倍工资期间 %s 至 %s 共 %d 个月（上限 11 个月）× 月薪 %.2f 元",
                start, end, months, monthlySalary);
        String basis = "《劳动合同法》第82条；《实施条例》第6、7条";
        String text = String.format("未签合同二倍工资差额：%.2f 元（%s，依据%s）", amount, summary, basis);
        return new CalcResult(String.format("%.2f", amount), summary, basis, text);
    }

    /**
     * 试用期合规检查（《劳动合同法》第19/20/83条）：法定上限 + 工资底线 + 违法赔偿金提示
     */
    public CalcResult probationCheck(int contractMonths, int probationMonths,
                                     Double probationSalary, Double contractSalary) {
        int legalMax = contractMonths < 3 ? 0 : (contractMonths < 12 ? 1 : (contractMonths < 36 ? 2 : 6));
        StringBuilder summary = new StringBuilder(String.format(
                "合同 %d 个月 → 试用期法定上限 %d 个月", contractMonths, legalMax));
        boolean illegal = false;
        if (probationMonths > legalMax) {
            illegal = true;
            summary.append(String.format("；约定 %d 个月超上限，违法", probationMonths));
            if (contractSalary != null) {
                summary.append(String.format("；已履行的超期部分可按转正月薪 %.2f 元/月主张赔偿金（第83条）", contractSalary));
            }
        } else {
            summary.append("；约定未超上限");
        }
        if (probationSalary != null && contractSalary != null && probationSalary < contractSalary * 0.8) {
            illegal = true;
            summary.append(String.format("；试用期工资 %.2f 低于约定工资 80%%（%.2f），违法（第20条）",
                    probationSalary, contractSalary * 0.8));
        }
        String basis = "《劳动合同法》第19、20、83条";
        String text = "试用期合规检查：" + summary + "（依据" + basis + "）";
        return new CalcResult(illegal ? "存在违法" : "合规", summary.toString(), basis, text);
    }

    /**
     * 年休假未休补偿（《职工带薪年休假条例》第3条 + 实施办法第10条）：
     * 应休天数按累计工龄 5/10/15 天档；未休部分按日工资 300%（含已发 100%，另补 200%）
     */
    public CalcResult annualLeaveCompensation(double totalWorkYears, int takenDays, double monthlySalary) {
        int entitled = totalWorkYears < 1 ? 0 : (totalWorkYears < 10 ? 5 : (totalWorkYears < 20 ? 10 : 15));
        int untaken = Math.max(0, entitled - takenDays);
        double daily = monthlySalary / 21.75;
        double extra = untaken * daily * 2;   // 300% 中含正常工作期间已发 100%，另补 200%
        String summary = String.format(
                "累计工龄 %.1f 年 → 应休 %d 天，已休 %d 天，未休 %d 天；日工资 %.2f 元 × 200%% 另补 = %.2f 元",
                totalWorkYears, entitled, takenDays, untaken, daily, extra);
        String basis = "《职工带薪年休假条例》第3、5条；《实施办法》第10条";
        String text = String.format("未休年休假补偿（另补 200%% 部分）：%.2f 元（%s，依据%s）", extra, summary, basis);
        return new CalcResult(String.format("%.2f", extra), summary, basis, text);
    }

    /**
     * 失业保险金领取月数（《社会保险法》第46条）：1-5 年→12 个月；5-10 年→18 个月；10 年以上→24 个月
     */
    public CalcResult unemploymentMonths(int contributionYears) {
        int months = contributionYears < 1 ? 0 : (contributionYears < 5 ? 12 : (contributionYears < 10 ? 18 : 24));
        String summary = months == 0
                ? "缴费不满 1 年，不符合领取条件（第45条）"
                : String.format("缴费 %d 年 → 最长领取 %d 个月；月金额按当地失业保险金标准（通常为最低工资的 70%%-90%%，各地不同）",
                        contributionYears, months);
        String basis = "《社会保险法》第45、46条";
        String text = "失业保险金领取月数：" + months + " 个月（" + summary + "，依据" + basis + "）";
        return new CalcResult(String.valueOf(months), summary, basis, text);
    }

    /**
     * 工伤一次性伤残补助金（《工伤保险条例》第35-37条）：一级 27 个月…十级 7 个月 × 本人工资；
     * 一次性工伤医疗/伤残就业补助金为地方标准，提示另查
     */
    public CalcResult workInjuryGrant(int disabilityGrade, double monthlySalary) {
        int[] monthsByGrade = {27, 25, 23, 21, 18, 16, 13, 11, 9, 7};
        if (disabilityGrade < 1 || disabilityGrade > 10) {
            throw new IllegalArgumentException("伤残等级应为 1-10 级");
        }
        int months = monthsByGrade[disabilityGrade - 1];
        double amount = months * monthlySalary;
        String summary = String.format("%d 级伤残 → %d 个月 × 本人工资 %.2f 元；一次性工伤医疗补助金/伤残就业补助金按地方标准另计",
                disabilityGrade, months, monthlySalary);
        String basis = "《工伤保险条例》第35-37条";
        String text = String.format("一次性伤残补助金：%.2f 元（%s，依据%s）", amount, summary, basis);
        return new CalcResult(String.format("%.2f", amount), summary, basis, text);
    }
}
