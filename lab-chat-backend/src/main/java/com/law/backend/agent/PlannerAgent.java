package com.law.backend.agent;

import com.law.backend.model.ModelRegistry;
import com.law.backend.model.ModelRouteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规划 Agent
 * <p>
 * <b>多轮指代消解</b>：多轮对话中用户说
 * "那第二种情况呢？"这类带指代的问题，拿原话去 embedding 检索召回全废。
 * 本 Agent 在<b>会话历史非空</b>时用廉价路线模型做一次结构化调用，同时产出：
 * <pre>
 *   { rewrittenQuery: 指代消解后的自包含问题, category: 民事/劳动/刑事/商事/程序/none }
 * </pre>
 * rewrittenQuery 进入 Plan 主子任务（下游强制检索用它），category 进入 intentHint
 * （下游 retrieveCitations 用它做元数据过滤）——<b>一次 LLM 调用两份产出</b>，
 * 与多意图拆解（subtasks）预留同一扩展位。
 * <p>
 * <b>触发条件与成本</b>：仅 history 非空（多轮）且 enabled 时触发；单轮对话零额外调用。
 * 改写失败（模型异常/输出空）降级为原问题，不阻断（项目降级信条）。
 * <p>
 * <b>骨架期约定保留</b>：decomposeByLlm（多意图拆解）仍未接通，Plan 恒单子任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerAgent implements Agent {

    /** 改写提示词：要求结构化输出 rewrittenQuery + category */
    private static final String REWRITE_PROMPT = """
            你是法律问答系统的查询改写器。根据会话历史把当前问题改写为自包含的检索查询：
            1. 消解指代（"它/第二种情况/上面说的"等替换为具体对象）；单轮或无指代时原样返回当前问题
            2. 改写结果必须是检索友好的法律术语表达，不要寒暄、不要解释
            3. 同时判断问题所属法律分类，只能是：民事/劳动/刑事/商事/程序/none 之一
            只输出 JSON：{"rewrittenQuery":"...","category":"..."}
            """;

    private final PlannerProperties properties;
    private final ModelRegistry modelRegistry;
    private final ModelRouteProperties routeProperties;

    /** 改写结构化输出（Spring AI .entity() 反序列化） */
    public record RewriteResult(String rewrittenQuery, String category) {
    }

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public AgentContext execute(AgentContext context) {
        String query = context.getQuestion();
        String category = "original";
        // 多轮（history 非空）才值得花一次廉价模型调用做指代消解
        if (properties.isEnabled() && context.getHistory() != null && !context.getHistory().isEmpty()) {
            RewriteResult rw = rewrite(query, context.getHistory());
            if (rw != null) {
                if (rw.rewrittenQuery() != null && !rw.rewrittenQuery().isBlank()) {
                    query = rw.rewrittenQuery();
                }
                if (rw.category() != null && !rw.category().isBlank()) {
                    category = rw.category();
                }
            }
        }
        context.setPlan(new Plan(List.of(new SubTask(query, category))));
        return context;
    }

    /**
     * 规划入口（保留骨架期签名供测试与未来多意图扩展）
     */
    public Plan plan(String question) {
        return Plan.single(question);
    }

    /**
     * 多轮指代消解 + 分类判断：廉价路线模型一次结构化调用
     *
     * @return 改写结果；失败返回 null（调用方降级为原问题）
     */
    private RewriteResult rewrite(String question, List<Message> history) {
        try {
            String[] target = routeProperties.resolve(properties.getRewriteRoute());
            String historyText = recentHistory(history);
            RewriteResult result = modelRegistry.getRawClient(target[0], target[1])
                    .prompt()
                    .system(REWRITE_PROMPT)
                    .user("会话历史：\n" + historyText + "\n当前问题：" + question)
                    .call()
                    .entity(RewriteResult.class);
            if (result != null && result.rewrittenQuery() != null && !result.rewrittenQuery().isBlank()) {
                log.info("Planner 改写命中: original={}, rewritten={}, category={}",
                        question, result.rewrittenQuery(), result.category());
            }
            return result;
        } catch (Exception e) {
            // 降级信条：改写失败不阻断，回落原问题
            log.warn("Planner 改写失败，降级为原问题: error={}", e.getMessage());
            return null;
        }
    }

    /** 取最近 4 条历史消息拼成文本（控制 token 成本） */
    private String recentHistory(List<Message> history) {
        List<Message> recent = history.size() > 4 ? history.subList(history.size() - 4, history.size()) : history;
        StringBuilder sb = new StringBuilder();
        for (Message m : recent) {
            if (m.getMessageType() == MessageType.USER) {
                sb.append("用户：").append(m.getText()).append('\n');
            } else if (m.getMessageType() == MessageType.ASSISTANT) {
                sb.append("助手：").append(m.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 骨架期遗留：多意图拆解仍未接通（返回 null 回落单子任务）。
     * 接通条件：出现"一次问三件事、单子任务检索答不全"实测案例。
     */
    private Plan decomposeByLlm(String question) {
        return null;
    }
}
