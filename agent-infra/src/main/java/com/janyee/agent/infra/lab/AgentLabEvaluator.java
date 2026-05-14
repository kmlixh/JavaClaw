package com.janyee.agent.infra.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评估器:基于一次完整 chat run 的 trace,按规则比对产出"通过/不通过"。
 *
 * <h3>支持的规则</h3>
 * <ul>
 *   <li>{@code {"type":"expected_output", "description":"<自然语言描述期望输出>"}}
 *       —— 调判官 LLM 看 input + 实际 assistant 输出 + 期望描述,返回 PASS/FAIL + 理由。
 *       这是给"只关心输入/输出契约"的用户用的主入口 —— 用户不指定调用哪些工具/做哪些步骤,
 *       那是 AI 自动调试要去摸索的过程,不是用户该写的需求。</li>
 *   <li>{@code {"type":"contains", "value":"<substr>", "ignoreCase":true}}
 *       —— 任一 assistant 输出含某子串(字面匹配,留作 power user 兜底)</li>
 *   <li>{@code {"type":"tool_called", "tool":"<name>", "minTimes":1}}
 *       —— 该工具的 EXECUTION_OUTCOME 中 success=true 计数 >= minTimes(power user 兜底)</li>
 * </ul>
 *
 * <p>规则全通过 = 用例通过;任一不通过 = 用例失败,会附带不通过的具体原因列表。
 * <b>没填任何 rules</b> 时,如果 case 上有 {@code expectedOutput} 字段(老格式兼容),
 * 自动按 expected_output 规则评估。</p>
 */
@Component
public class AgentLabEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AgentLabEvaluator.class);
    private static final Duration JUDGE_TIMEOUT = Duration.ofMinutes(2);

    private final ObjectMapper objectMapper;
    private final LlmProvider llmProvider;

    public AgentLabEvaluator(ObjectMapper objectMapper, LlmProvider llmProvider) {
        this.objectMapper = objectMapper;
        this.llmProvider = llmProvider;
    }

    /**
     * @param caseId          测试用例 id
     * @param input           输入文本(原样回写)
     * @param trace           {@link AgentLabTraceCollector#collect(String, String)} 产出的 trace 节点
     * @param rulesJson       该用例的规则数组
     * @param judgeLlmConfigId 走 expected_output 规则时调用判官 LLM 的 config id(传 null = 走 default)
     * @param judgeLlmModel    判官 LLM 选定的 model(LLM 配置可挂多 model 时区分用,null 取 LLM 内部默认)
     */
    public CaseResult evaluate(String caseId, String input, JsonNode trace, JsonNode rulesJson, String judgeLlmConfigId, String judgeLlmModel) {
        String fullText = joinAssistantText(trace);
        Map<String, Integer> toolSuccessCounts = countSuccessfulTools(trace);

        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<Map<String, String>> judgeReasoning = new ArrayList<>();
        if (rulesJson != null && rulesJson.isArray() && rulesJson.size() > 0) {
            for (JsonNode rule : rulesJson) {
                String type = rule.path("type").asText("");
                switch (type.toLowerCase(Locale.ROOT)) {
                    case "expected_output" -> {
                        String description = rule.path("description").asText("");
                        if (description.isBlank()) {
                            failed.add("expected_output rule has empty description");
                            break;
                        }
                        JudgeOutcome outcome = judgeWithLlm(judgeLlmConfigId, judgeLlmModel, input, fullText, description);
                        Map<String, String> note = new LinkedHashMap<>();
                        note.put("expectedOutput", description);
                        note.put("verdict", outcome.passed ? "PASS" : "FAIL");
                        note.put("reasoning", outcome.reasoning);
                        judgeReasoning.add(note);
                        (outcome.passed ? passed : failed).add("expected_output " + (outcome.passed ? "✓" : "✗") + ": " + outcome.reasoning);
                    }
                    case "contains" -> {
                        String value = rule.path("value").asText("");
                        boolean ignoreCase = rule.path("ignoreCase").asBoolean(false);
                        boolean ok = ignoreCase
                                ? fullText.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))
                                : fullText.contains(value);
                        (ok ? passed : failed).add("contains '" + value + "' " + (ok ? "✓" : "✗"));
                    }
                    case "tool_called" -> {
                        String tool = rule.path("tool").asText("");
                        int minTimes = rule.path("minTimes").asInt(1);
                        int actual = toolSuccessCounts.getOrDefault(tool, 0);
                        boolean ok = actual >= minTimes;
                        (ok ? passed : failed).add("tool_called '" + tool + "' need>=" + minTimes
                                + " got=" + actual + " " + (ok ? "✓" : "✗"));
                    }
                    default -> failed.add("unsupported rule type: " + type);
                }
            }
        }

        boolean allPassed = failed.isEmpty() && (rulesJson == null || !rulesJson.isArray() || rulesJson.size() > 0);
        // 没规则的用例不应该被算作"通过"——应该是配置错误,以失败计,免得迭代直接收敛假阳性
        if (rulesJson == null || !rulesJson.isArray() || rulesJson.size() == 0) {
            failed.add("no rules defined for this case");
            allPassed = false;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("input", input);
        result.put("passed", allPassed);
        result.put("rulesPassed", passed);
        result.put("rulesFailed", failed);
        result.put("runStatus", trace.path("runStatus").asText(""));
        result.put("assistantSnippet", truncate(fullText, 600));
        result.put("toolSuccessCounts", toolSuccessCounts);
        if (!judgeReasoning.isEmpty()) {
            result.put("judgeReasoning", judgeReasoning);
        }
        return new CaseResult(allPassed, result);
    }

    /** 调判官 LLM 评判 actual vs expected。判官 prompt 强约束输出 JSON {verdict, reasoning}。 */
    private JudgeOutcome judgeWithLlm(String judgeLlmConfigId, String judgeLlmModel, String input, String actualOutput, String expectedDescription) {
        String prompt = """
                你是一个测试评估官。输入是用户的请求,actualOutput 是 Agent 实际生成的回答,
                expectedDescription 是用户对回答的自然语言要求(描述应该返回什么样的内容)。
                你需要判断 actualOutput 是否满足 expectedDescription 的要求 —— 关注语义和事实,
                而非字面匹配。如果 actualOutput 缺少关键信息、出现事实错误、或没有回答到点上,判 FAIL;
                否则判 PASS。

                严格输出一行 JSON,不要任何多余文字、不要 markdown fence:
                {"verdict":"PASS","reasoning":"理由(中文,30 字以内)"}

                ## 用户输入(input)
                %s

                ## Agent 实际输出(actualOutput)
                %s

                ## 用户期望(expectedDescription)
                %s
                """.formatted(safe(input), safe(actualOutput), safe(expectedDescription));

        try {
            List<LlmStreamEvent> events = new ArrayList<>();
            llmProvider.chatStream(new LlmChatRequest(judgeLlmModel, judgeLlmConfigId, prompt, List.of()))
                    .doOnNext(events::add)
                    .blockLast(JUDGE_TIMEOUT);
            String fullText = events.stream()
                    .filter(e -> "token".equalsIgnoreCase(e.type()))
                    .map(LlmStreamEvent::content)
                    .reduce("", (a, b) -> a + (b == null ? "" : b));
            String jsonText = stripJsonFence(fullText).trim();
            // 容错:LLM 可能输出多行,只取第一个 { ... } 段
            int start = jsonText.indexOf('{');
            int end = jsonText.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonText = jsonText.substring(start, end + 1);
            }
            JsonNode parsed = objectMapper.readTree(jsonText);
            String verdict = parsed.path("verdict").asText("FAIL").toUpperCase(Locale.ROOT);
            String reasoning = parsed.path("reasoning").asText("(无理由)");
            return new JudgeOutcome("PASS".equals(verdict), reasoning);
        } catch (Exception err) {
            log.warn("agent.lab.judge_failed reason={}", err.getMessage());
            return new JudgeOutcome(false, "判官 LLM 调用失败:" + err.getMessage());
        }
    }

    private String stripJsonFence(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return trimmed.substring(firstNl + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String safe(String s) { return s == null ? "" : s; }

    private String joinAssistantText(JsonNode trace) {
        StringBuilder sb = new StringBuilder();
        JsonNode arr = trace.path("assistantTexts");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t.asText(""));
            }
        }
        return sb.toString();
    }

    private Map<String, Integer> countSuccessfulTools(JsonNode trace) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonNode calls = trace.path("toolCalls");
        if (!calls.isArray()) return counts;
        for (JsonNode call : calls) {
            if (!"EXECUTION_OUTCOME".equalsIgnoreCase(call.path("phase").asText(""))) continue;
            if (!call.path("success").asBoolean(false)) continue;
            String tool = call.path("tool").asText("");
            if (!tool.isBlank()) {
                counts.merge(tool, 1, Integer::sum);
            }
        }
        return counts;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    public record CaseResult(boolean passed, Map<String, Object> resultMap) {
    }

    private record JudgeOutcome(boolean passed, String reasoning) {
    }
}
