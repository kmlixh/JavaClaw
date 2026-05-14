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
import java.util.List;

/**
 * 调用 meta-LLM 让它产 Agent + Skill 的设计 JSON。两类入口:
 * <ul>
 *   <li><b>初次设计</b>:只有 goal + test cases,LLM 从零给配置</li>
 *   <li><b>修订</b>:goal + test cases + 上一轮设计 + 上一轮**完整 chat run trace**(含 assistant
 *       输出 / tool 调用序列 / SkillGuard 拒绝原因 / run 最终状态),LLM 看着 trace 给改进版</li>
 * </ul>
 *
 * <p>输出严格 JSON:
 * <pre>
 *   { "agent": { agentId, displayName, description, systemPrompt, agentMarkdown },
 *     "skills": [ { skillName, description, promptTemplate, configJson, triggerKeywords } ],
 *     "rationale": "改动说明,或 'ABORT: <原因>'" }
 * </pre>
 * </p>
 */
@Component
public class MetaLlmDesigner {

    private static final Logger log = LoggerFactory.getLogger(MetaLlmDesigner.class);
    private static final Duration META_CALL_TIMEOUT = Duration.ofMinutes(3);

    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;

    public MetaLlmDesigner(LlmProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    public JsonNode design(String llmConfigId, String prompt) {
        return design(llmConfigId, null, prompt);
    }

    public JsonNode design(String llmConfigId, String model, String prompt) {
        List<LlmStreamEvent> events = new ArrayList<>();
        try {
            llmProvider.chatStream(new LlmChatRequest(model, llmConfigId, prompt, List.of()))
                    .doOnNext(events::add)
                    .blockLast(META_CALL_TIMEOUT);
        } catch (Exception error) {
            throw new RuntimeException("meta-LLM call failed: " + error.getMessage(), error);
        }
        for (LlmStreamEvent event : events) {
            if ("error".equalsIgnoreCase(event.type())) {
                throw new RuntimeException("meta-LLM error event: " + event.content());
            }
        }
        String fullText = events.stream()
                .filter(e -> "token".equalsIgnoreCase(e.type()))
                .map(LlmStreamEvent::content)
                .reduce("", (a, b) -> a + (b == null ? "" : b));
        if (fullText.isBlank()) {
            throw new RuntimeException("meta-LLM returned empty text");
        }
        String jsonText = stripJsonFence(fullText);
        try {
            return objectMapper.readTree(jsonText);
        } catch (Exception parseError) {
            log.warn("meta-LLM design json parse failed, raw output:\n{}", fullText);
            throw new RuntimeException("meta-LLM output is not valid JSON: " + parseError.getMessage(), parseError);
        }
    }

    private String stripJsonFence(String text) {
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

    /**
     * 从用户的约束规则 + 参考文档 + 目标描述 派生 N 条测试场景。
     * 输出严格 JSON 数组,每条 {id, input, rules:[{type:expected_output, description:"..."}]} —— 跟
     * AgentLabEvaluator 评估格式对齐,后续 runner 直接拿来跑。每条 input 是 Agent 实际能收到的
     * 用户消息,description 描述期望输出的语义/事实/格式。
     */
    public String buildDeriveScenariosPrompt(String goal, String constraintRules, String referenceDocuments) {
        String docs = referenceDocuments == null || referenceDocuments.isBlank()
                ? "(无)"
                : referenceDocuments;
        return """
            你是测试场景生成器。任务里用户没有手写测试用例,只给了若干自然语言约束规则。
            请你据"目标描述 + 约束规则 + 参考文档"派生 3 条覆盖关键场景的测试用例。
            每条用例 = (用户输入文本, 期望输出的自然语言描述)。判官 LLM 后续会按 description 做语义评判。

            注意:
            * 不要凭空设定业务约束,只在用户给的方向上生成。
            * 输入要写得像真实用户提问,而不是描述"考察什么";输出 description 要可验证(包含什么事实 / 什么格式 / 什么口径)。
            * id 用 case-1 / case-2 / case-3。
            * 严格输出下面 JSON 数组,**禁止**任何其它文字 / Markdown 包裹:

            [
              {
                "id": "case-1",
                "input": "<用户实际可能发的提问>",
                "rules": [
                  { "type": "expected_output", "description": "<期望输出应包含什么 / 什么格式>" }
                ]
              }
            ]

            ## 目标描述
            %s

            ## 约束规则
            %s

            ## 参考文档
            %s
            """.formatted(safe(goal), safe(constraintRules), docs);
    }

    private String safe(String s) { return s == null ? "" : s; }

    /** 初次设计 prompt。targetAgentId 必须保持不变(系统已经建好占位 agent / 选好已有 agent)。 */
    public String buildInitialPrompt(String goal, String testCasesJson, String targetAgentId, String mode) {
        return """
            你是一个 AI Agent 设计师。系统会用你的设计跑一组测试用例(完整经过 plan/tool loop/SkillGuard),
            然后告诉你哪些通过、哪些失败。请基于目标设计 Agent 配置(可选附带 1 至 N 个 Skill 配置)。

            【目标描述】
            %s

            【测试用例】(系统会真实跑这些用例)
            %s

            【约束】
            * agentId 必须填:%s (这个 agent 已经存在,你设计的是它的 systemPrompt 等内容字段)
            * 模式:%s (EXISTING=直接改已有 agent;NEW=新 agent 从零建;CLONE_FROM=已经从源克隆了起点)
            * Skill 是可选的;如果加,skillName 全部以 "skill.lab." 开头避免跟生产冲突
            * 严格输出下面这个 JSON,**禁止**包含其他文字 / Markdown 包裹:

            {
              "agent": {
                "agentId": "%s",
                "displayName": "<人话标题>",
                "description": "<一句话说明>",
                "systemPrompt": "<给 agent 的系统提示词;明确告诉它如何处理输入、调用哪些工具、输出格式>",
                "agentMarkdown": "<可选 AGENT.md 内容,可空字符串>"
              },
              "skills": [
                {
                  "skillName": "skill.lab.xxx",
                  "description": "<skill 用途>",
                  "promptTemplate": "<提示词,会拼到 agent 上下文>",
                  "configJson": "{}",
                  "triggerKeywords": "[]"
                }
              ],
              "rationale": "<设计要点的简短说明>"
            }
            """.formatted(goal, testCasesJson, targetAgentId, mode, targetAgentId);
    }

    // ---------------------------------------------------------------------------------------
    // Skill 模式 prompt (Phase 1.5 主路径)

    /** 初次设计单个 Skill 的 prompt。 */
    public String buildInitialSkillPrompt(String goal, String testCasesJson,
                                          String skillName, String hostAgentId, String mode) {
        return """
            你是一个 Skill 设计师。系统会把你的设计写入名为 %s 的 Skill,然后让 host agent (%s)
            跑一组测试用例(完整经过 plan/tool loop/SkillGuard),再告诉你结果。请基于目标设计
            该 Skill 的 promptTemplate + configJson。

            【目标描述】
            %s

            【测试用例】(系统会真实跑)
            %s

            【约束】
            * skillName 必须填:%s (这是 task 绑定的 skill 名,不要改)
            * 不要改其他 Agent / Skill 的内容,只设计这一个 Skill
            * configJson 是 JSON 对象字符串(可放 whitelistTables / planStepIds / planStepRules /
              sqlTemplates 等);如不需要可填 "{}"
            * triggerKeywords 是 JSON 数组字符串(LLM 看到用户消息含某些关键字时会激活该 skill)
            * 模式:%s
            * 严格输出下面这个 JSON,**禁止**包含其他文字 / Markdown 包裹:

            {
              "skill": {
                "skillName": "%s",
                "description": "<一句话说明能力>",
                "promptTemplate": "<skill 提示词,会拼到 host agent 的上下文里>",
                "configJson": "{}",
                "triggerKeywords": "[]"
              },
              "rationale": "<为什么这样设计的简短说明>"
            }
            """.formatted(skillName, hostAgentId, goal, testCasesJson, skillName, mode, skillName);
    }

    /** Skill 修订 prompt:把上一轮 skill 配置 + 真实 chat run trace 喂回去。 */
    public String buildRefineSkillPrompt(String goal, String testCasesJson,
                                         String skillName, String hostAgentId,
                                         String previousSkillJson,
                                         String previousTestResultsJson, String runTracesJson) {
        return """
            你是 Skill 设计师。上一轮你设计的 Skill 跑了真实 chat,但没全部通过测试。
            完整 trace 在下面(每个失败用例的 plan / tool loop / SkillGuard 实际行为)。

            【目标描述】
            %s

            【测试用例】
            %s

            【上一轮 Skill 配置】
            %s

            【上一轮评估摘要】
            %s

            【上一轮完整运行 trace】
            %s

            【如何改】
            * 看 trace.runStatus、trace.assistantTexts、trace.toolCalls 里的 success/reason/errorMessage,
              找到失败根因(prompt 没说清楚 / 调了被白名单拒绝的表 / sqlTemplates 列名错 等),
              改 promptTemplate 或 configJson
            * **不要**改 skillName(必须保持 %s);其他 Agent / Skill 不要碰
            * 如果发现"完全做不到"(数据缺失 / 工具白名单不开放 / 目标本身矛盾),
              rationale 写成 "ABORT: <具体原因>",这一轮算失败终止

            严格输出下面 JSON,**禁止**其他文字:

            {
              "skill": {
                "skillName": "%s",
                "description": "...",
                "promptTemplate": "...",
                "configJson": "{}",
                "triggerKeywords": "[]"
              },
              "rationale": "<这一轮改了什么 / 为什么 / 或 ABORT: ...>"
            }
            """.formatted(goal, testCasesJson, previousSkillJson,
                    previousTestResultsJson, runTracesJson, skillName, skillName);
    }

    // ---------------------------------------------------------------------------------------
    // Dual 模式 prompt:同时改 Agent + Skill (allowAgentEvolution=true 时启用)

    /** Dual 初次设计:同时给 Host Agent 和 Skill 起手配置。 */
    public String buildInitialDualPrompt(String goal, String testCasesJson,
                                         String hostAgentId, String currentAgentSystemPrompt,
                                         String skillName, String mode) {
        return """
            你既是 Host Agent 设计师又是 Skill 设计师。系统会把你的 Agent + Skill 设计写入,
            然后 Host Agent (%s) 会跑一组测试用例(完整经过 plan/tool loop/SkillGuard),
            告诉你结果。请同时设计 Agent 的 systemPrompt + Skill 的 promptTemplate / configJson。

            【目标描述】
            %s

            【测试用例】
            %s

            【Host Agent 当前状态(用户提供的种子提示词或克隆来的初稿,可在此基础上扩写)】
            %s

            【约束】
            * agentId 必须填:%s,skillName 必须填:%s,这两个都不能改
            * 模式:%s
            * 严格输出下面 JSON,**禁止**包含其他文字:

            {
              "agent": {
                "agentId": "%s",
                "displayName": "<人话标题>",
                "description": "<一句话>",
                "systemPrompt": "<给 host agent 的系统提示词>",
                "agentMarkdown": "<可选 AGENT.md 内容,可空字符串>"
              },
              "skill": {
                "skillName": "%s",
                "description": "<能力说明>",
                "promptTemplate": "<skill 提示词>",
                "configJson": "{}",
                "triggerKeywords": "[]"
              },
              "rationale": "<设计要点说明>"
            }
            """.formatted(hostAgentId, goal, testCasesJson,
                    currentAgentSystemPrompt == null || currentAgentSystemPrompt.isBlank() ? "(空,你需要从零写)" : currentAgentSystemPrompt,
                    hostAgentId, skillName, mode, hostAgentId, skillName);
    }

    /** Dual 修订:基于上一轮 trace 同时改 Agent 和 Skill。 */
    public String buildRefineDualPrompt(String goal, String testCasesJson,
                                        String hostAgentId, String skillName,
                                        String previousAgentJson, String previousSkillJson,
                                        String previousTestResultsJson, String runTracesJson) {
        return """
            你既是 Host Agent 设计师又是 Skill 设计师。上一轮你的设计跑了真实 chat 但没全部通过,
            完整 trace 在下面。看 trace 找问题:可能改 Agent 的 systemPrompt,或 Skill 的
            promptTemplate / configJson,或两者都改。

            【目标描述】
            %s

            【测试用例】
            %s

            【上一轮 Agent 配置】
            %s

            【上一轮 Skill 配置】
            %s

            【上一轮评估摘要】
            %s

            【上一轮完整运行 trace】
            %s

            【如何改】
            * 看 trace.runStatus / assistantTexts / toolCalls 找根因(prompt 不清楚 / 工具被白名单拒绝
              / sqlTemplates 列名错 等),决定改 Agent 还是 Skill 还是两者
            * **不要**改 agentId(必须保持 %s)和 skillName(必须保持 %s)
            * 如果发现"完全做不到",rationale 写 "ABORT: <原因>"

            严格输出下面 JSON,**禁止**其他文字:

            {
              "agent": { "agentId": "%s", "displayName": "...", "description": "...", "systemPrompt": "...", "agentMarkdown": "..." },
              "skill": { "skillName": "%s", "description": "...", "promptTemplate": "...", "configJson": "{}", "triggerKeywords": "[]" },
              "rationale": "<改了什么 / 为什么 / 或 ABORT: ...>"
            }
            """.formatted(goal, testCasesJson, previousAgentJson, previousSkillJson,
                    previousTestResultsJson, runTracesJson, hostAgentId, skillName, hostAgentId, skillName);
    }

    // ---------------------------------------------------------------------------------------
    // Agent 模式 prompt(老路径,target_type=AGENT 用)

    /**
     * 修订 prompt:把上一轮的设计 + **完整运行 trace** 喂回去,LLM 看 trace 决定改什么。
     * trace 是 JSON array(每个测试用例一个 trace),含 runStatus / assistantTexts / toolCalls(EXECUTION_OUTCOME
     * 含 success+errorMessage / POLICY_DECISION 含 reason 即 SkillGuard 拒绝原因)。
     */
    public String buildRefinePrompt(String goal, String testCasesJson, String targetAgentId,
                                    String previousAgentJson, String previousSkillsJson,
                                    String previousTestResultsJson, String runTracesJson) {
        return """
            你是一个 AI Agent 设计师。上一轮你设计的 Agent + Skill 跑了真实 chat,但没全部通过测试。
            下面给你完整的运行 trace(每个失败用例的 plan/tool loop/SkillGuard 的实际行为),你看 trace
            找出问题,改 Agent 或 Skill 的内容字段,产出新版本。

            【目标描述】
            %s

            【测试用例】
            %s

            【上一轮设计的 Agent】
            %s

            【上一轮设计的 Skills】
            %s

            【上一轮评估摘要(passed/failed 列表)】
            %s

            【上一轮完整运行 trace(每个用例的真实运行)】
            %s

            【如何改】
            * 看 trace.runStatus、trace.assistantTexts、trace.toolCalls 里的 success/reason/errorMessage
              → 找到失败根因(prompt 没说清楚要调什么工具?调了工具但被 SkillGuard 拒?输出格式不对?)
            * 对应改 Agent.systemPrompt / Skill.promptTemplate / Skill.configJson 等内容字段
            * **不要**改 agentId / skillName(它们必须跟上轮一致,否则系统找不到目标)
            * 如果发现"完全做不到"(比如要查的数据库表不存在、需要的工具白名单根本没开放、目标本身矛盾),
              把 rationale 写成 "ABORT: <具体原因>",这一轮算失败终止,不再迭代

            严格输出下面 JSON,**禁止**其他文字:

            {
              "agent": { "agentId": "%s", "displayName": "...", "description": "...", "systemPrompt": "...", "agentMarkdown": "..." },
              "skills": [ { "skillName": "skill.lab.xxx", "description": "...", "promptTemplate": "...", "configJson": "{}", "triggerKeywords": "[]" } ],
              "rationale": "<这一轮改了什么 / 为什么 / 或 ABORT: ...>"
            }
            """.formatted(goal, testCasesJson, previousAgentJson, previousSkillsJson,
                    previousTestResultsJson, runTracesJson, targetAgentId);
    }
}
