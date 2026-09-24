package com.law.backend.controller;

import com.law.backend.service.CalculatorService;
import com.law.backend.service.CalculatorService.CalcResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 法律工具箱 Controller（前端工程配套增量：支撑"法律工具箱"页面 4 个计算器）
 * <p>
 * 计算逻辑单份沉淀在 {@link CalculatorService}（与 LLM 工具 LegalTools 共用），
 * 本 Controller 只做 REST 包装：入参 record 反序列化 + 统一返回结构 + 参数错误转 400。
 */
@Slf4j
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
public class ToolController {

    private final CalculatorService calculatorService;

    /** 经济补偿金计算器入参 */
    public record CompensationReq(double workYears, double monthlySalary, Double localAvgSalary) {
    }

    /** 加班费计算器入参 */
    public record OvertimeReq(double baseSalary, double weekdayHours, double weekendHours, double holidayHours) {
    }

    /** 诉讼时效计算器入参 */
    public record LimitationReq(String eventDate) {
    }

    /** 案件受理费计算器入参 */
    public record CourtFeeReq(double claimAmount) {
    }

    /* ---- 劳动法专精工具入参 ---- */
    public record SocialInsuranceReq(double actualSalary, double contributionBase, int months) {
    }

    public record FundBackpayReq(double actualSalary, double contributionBase, int months, Double fundRate) {
    }

    public record ArbitrationLimitationReq(String knowDate, boolean wageArrears, String endDate) {
    }

    public record DoubleWageReq(String startDate, String signDate, double monthlySalary) {
    }

    public record ProbationReq(int contractMonths, int probationMonths, Double probationSalary, Double contractSalary) {
    }

    public record AnnualLeaveReq(double totalWorkYears, int takenDays, double monthlySalary) {
    }

    public record UnemploymentReq(int contributionYears) {
    }

    public record WorkInjuryReq(int disabilityGrade, double monthlySalary) {
    }

    /** 经济补偿金（劳动合同法第47条） */
    @PostMapping("/compensation")
    public Map<String, Object> compensation(@RequestBody CompensationReq req) {
        return ok(() -> calculatorService.severance(req.workYears(), req.monthlySalary(), req.localAvgSalary()));
    }

    /** 加班费（劳动法第44条） */
    @PostMapping("/overtime")
    public Map<String, Object> overtime(@RequestBody OvertimeReq req) {
        return ok(() -> calculatorService.overtime(req.baseSalary(), req.weekdayHours(), req.weekendHours(), req.holidayHours()));
    }

    /** 诉讼时效（民法典第188条） */
    @PostMapping("/statute-limit")
    public Map<String, Object> statuteLimit(@RequestBody LimitationReq req) {
        return ok(() -> calculatorService.limitation(req.eventDate()));
    }

    /** 案件受理费（诉讼费用交纳办法第13条） */
    @PostMapping("/court-fee")
    public Map<String, Object> courtFee(@RequestBody CourtFeeReq req) {
        return ok(() -> calculatorService.courtFee(req.claimAmount()));
    }

    /* ---- 劳动法专精工具端点 ---- */

    @PostMapping("/social-insurance-backpay")
    public Map<String, Object> socialInsuranceBackpay(@RequestBody SocialInsuranceReq req) {
        return ok(() -> calculatorService.socialInsuranceBackpay(
                req.actualSalary(), req.contributionBase(), req.months()));
    }

    @PostMapping("/fund-backpay")
    public Map<String, Object> fundBackpay(@RequestBody FundBackpayReq req) {
        return ok(() -> calculatorService.fundBackpay(
                req.actualSalary(), req.contributionBase(), req.months(), req.fundRate()));
    }

    @PostMapping("/arbitration-limitation")
    public Map<String, Object> arbitrationLimitation(@RequestBody ArbitrationLimitationReq req) {
        return ok(() -> calculatorService.arbitrationLimitation(req.knowDate(), req.wageArrears(), req.endDate()));
    }

    @PostMapping("/double-wage")
    public Map<String, Object> doubleWage(@RequestBody DoubleWageReq req) {
        return ok(() -> calculatorService.doubleWage(req.startDate(), req.signDate(), req.monthlySalary()));
    }

    @PostMapping("/probation-check")
    public Map<String, Object> probationCheck(@RequestBody ProbationReq req) {
        return ok(() -> calculatorService.probationCheck(
                req.contractMonths(), req.probationMonths(), req.probationSalary(), req.contractSalary()));
    }

    @PostMapping("/annual-leave")
    public Map<String, Object> annualLeave(@RequestBody AnnualLeaveReq req) {
        return ok(() -> calculatorService.annualLeaveCompensation(req.totalWorkYears(), req.takenDays(), req.monthlySalary()));
    }

    @PostMapping("/unemployment-months")
    public Map<String, Object> unemploymentMonths(@RequestBody UnemploymentReq req) {
        return ok(() -> calculatorService.unemploymentMonths(req.contributionYears()));
    }

    @PostMapping("/work-injury-grant")
    public Map<String, Object> workInjuryGrant(@RequestBody WorkInjuryReq req) {
        return ok(() -> calculatorService.workInjuryGrant(req.disabilityGrade(), req.monthlySalary()));
    }

    /** 统一执行 + 参数错误转 400（如日期格式非法） */
    private Map<String, Object> ok(java.util.function.Supplier<CalcResult> supplier) {
        try {
            return Map.of("code", 200, "data", supplier.get());
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        }
    }
}
