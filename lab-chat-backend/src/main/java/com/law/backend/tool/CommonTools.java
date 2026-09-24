package com.law.backend.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通用工具类（@Tool 注解风格，替代旧版 FunctionConfig 的 Function Bean 写法）
 * <p>
 * <b>新旧写法对比</b>：
 * <ul>
 *   <li>旧：每个工具 = @Bean Function + @Description + Request POJO，调用点写 .toolNames("字符串")</li>
 *   <li>新：每个工具 = 一个 @Tool 注解的普通方法，参数用 @ToolParam 描述，
 *       框架自动从方法签名生成 JSON Schema，无需 Request 类、无字符串魔法值</li>
 * </ul>
 * <p>
 * <b>挂载方式</b>：
 * 通用工具（时间、计算）在 {@link com.law.backend.config.AiConfig} 中通过
 * {@code ChatClient.builder(chatModel).defaultTools(commonTools)} 全局挂载，
 * 之后所有 chatClient.prompt() 调用自动携带这些工具，业务代码无需再写 .toolNames。
 * <p>
 * <b>工具分组建议</b>：
 * 工具变多后按领域拆分多个类（如 TimeTools / MathTools / LegalTools），
 * 通用工具走 defaultTools，领域工具按需 .tools() 挂载（工具越多越耗 token 且易误导 LLM）。
 */
@Slf4j
@Component
public class CommonTools {

    /**
     * 工具一：获取当前时间
     * <p>
     * LLM 不知道"今天是几号"，问时间类问题时会自动调用本工具。
     * format 参数为可选（required = false），LLM 不传时用默认格式。
     */
    @Tool(description = "获取服务器当前日期时间，支持自定义格式")
    public String getCurrentTime(
            @ToolParam(description = "时间格式，如 yyyy-MM-dd HH:mm:ss，不传则用默认格式", required = false) String format) {
        String pattern = format == null || format.isBlank() ? "yyyy-MM-dd HH:mm:ss" : format;
        String result = LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
        log.info("Tool Calling: getCurrentTime, format={}, result={}", format, result);
        return result;
    }

    /**
     * 工具二：表达式计算
     * <p>
     * 演示 LLM 从自然语言中提取参数：用户说"帮我算一下 12*5+8"，
     * LLM 生成 {"expression": "12*5+8"} 调用本工具。
     * <p>
     * 说明：JDK 15 起移除 Nashorn JS 引擎，故不依赖 ScriptEngine，
     * 用内置的递归下降解析器实现（支持加减乘除与括号）。
     */
    @Tool(description = "计算数学表达式的值，支持加减乘除和括号，如 12*5+8")
    public String calculate(
            @ToolParam(description = "数学表达式，如 12*5+8，只支持数字和加减乘除括号") String expression) {
        String result = String.valueOf(new ExprParser(expression).parse());
        log.info("Tool Calling: calculate, expression={}, result={}", expression, result);
        return result;
    }

    /**
     * 简易四则运算解析器（递归下降）
     * <p>
     * <b>为什么不用 ScriptEngine？</b>
     * JDK 15 起移除了 Nashorn JS 引擎，所以这里手写一个极简的递归下降解析器，
     * 顺便复习编译原理中"文法 → 递归函数"的经典转换方法。
     * <p>
     * <b>文法定义（按优先级从低到高分层）</b>：
     * <pre>
     *   expr   = term (('+'|'-') term)*      // 第 1 层：加减，优先级最低
     *   term   = factor (('*'|'/') factor)*  // 第 2 层：乘除
     *   factor = '(' expr ')' | number       // 第 3 层：括号/数字，优先级最高
     * </pre>
     * 每一条文法规则对应一个同名方法：乘除的输入是 factor、加减的输入是 term，
     * 方法调用层次越深优先级越高，从而保证"先乘除后加减、括号最优先"。
     * <p>
     * <b>工作方式</b>：
     * 只用一个下标 {@code pos} 从左到右扫描字符串（单遍扫描，O(n) 时间），
     * 每消费一个字符就 pos++，解析到哪就停在哪个位置。
     */
    private static class ExprParser {

        /** 待解析的原始表达式字符串，如 "12*5+8" */
        private final String s;

        /** 当前扫描位置（下标），指向下一个待处理的字符；解析过程就是不断后移它 */
        private int pos;

        /**
         * 构造器：只保存表达式，扫描从下标 0 开始
         *
         * @param s 数学表达式字符串
         */
        ExprParser(String s) {
            this.s = s;
        }

        /**
         * 解析入口：整条表达式解析完之后，位置必须恰好停在字符串末尾，
         * 否则说明中间混入了无法识别的字符（如 "12*5+8#"），抛出异常。
         *
         * @return 表达式的计算结果
         */
        double parse() {
            double result = expr();
            if (pos < s.length()) {
                throw new IllegalArgumentException("非法表达式: " + s);
            }
            return result;
        }

        /**
         * 处理加减法（优先级最低的一层）：
         * 先解析一个 term，之后只要遇到 '+' 或 '-' 就继续解析下一个 term 并累加/累减。
         * <p>
         * 例："12*5+8-2" → term 算出 60 → 遇 '+' 加 term 的 8 → 遇 '-' 减 term 的 2 → 返回 66。
         * 注意：加减是从左到右运算的，所以边扫边算，而不是先收集再统一算。
         */
        private double expr() {
            double result = term();
            while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                char op = s.charAt(pos++);   // 取出运算符并后移位置
                double right = term();       // 解析运算符右侧的操作数
                result = op == '+' ? result + right : result - right;
            }
            return result;
        }

        /**
         * 处理乘除法（中间层，优先级高于加减）：
         * 先解析一个 factor，之后只要遇到 '*' 或 '/' 就继续解析下一个 factor 并累乘/累除。
         * <p>
         * 例："12*5/3" → factor 得 12 → 遇 '*' 乘 factor 的 5 → 遇 '/' 除 factor 的 3 → 返回 20。
         * 乘除同样从左到右运算（8/4*2 = 4，不是 8/8）。
         */
        private double term() {
            double result = factor();
            while (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                char op = s.charAt(pos++);   // 取出运算符并后移位置
                double right = factor();     // 解析运算符右侧的操作数
                result = op == '*' ? result * right : result / right;
            }
            return result;
        }

        /**
         * 处理最小单元（优先级最高的一层），有两种情况：
         * <ol>
         *   <li>遇 '(' → 递归调用 expr() 解析括号内表达式（递归下降名字的由来），
         *       解析完必须吃掉 ')'，否则报"括号不匹配"</li>
         *   <li>否则 → 连续读取数字和小数点，组装成一个数字字面量
         *       （不支持负数/科学计数法，学习演示足够用）</li>
         * </ol>
         *
         * @return 该单元（括号表达式或数字）的值
         */
        private double factor() {
            // 情况 1：括号 —— 括号相当于"递归入口"，让 expr 能出现在任何嵌套深度
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;                                   // 吃掉 '('，进入括号内部
                double result = expr();                  // 括号内的内容当作一条完整表达式递归解析
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new IllegalArgumentException("括号不匹配: " + s);
                }
                pos++;                                   // 吃掉 ')'，回到上一层继续
                return result;
            }
            // 情况 2：数字 —— 从当前位置开始，尽可能多地读取数字和小数点
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {                          // 一个数字字符都没读到，说明遇到了非法字符
                throw new IllegalArgumentException("非法表达式: " + s);
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
