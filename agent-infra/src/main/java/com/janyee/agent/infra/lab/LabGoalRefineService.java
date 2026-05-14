package com.janyee.agent.infra.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.AuthService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import com.janyee.agent.runtime.lab.LabRefineGoalRequest;
import com.janyee.agent.runtime.lab.LabRefineGoalResponse;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

/**
 * 临时对话方式扩充/优化 Lab 任务的"目标描述"。
 *
 * <p>不持久化任何东西 —— 这只是个 "designer 助手 LLM call",请求里塞 draft + history + 新消息,
 * 响应里返回新 draft + assistant 解释。前端按对话气泡展示,用户可多轮迭代,
 * 满意了把最新 draft 写回 labForm.goalDescription。</p>
 *
 * <p>线程模型:全程响应式 —— controller 直接返回这里的 Mono,WebFlux 自然在 reactor-http
 * 线程上 subscribe;LlmProvider.chatStream 本来就是 Flux,我们只做 reduce / map / timeout,
 * 没有任何 block 调用,WebFlux 的 BlockHound 不会触发。之前用 blockLast → IllegalStateException。</p>
 *
 * <p>权限:跟 AgentLabService 一致 —— 任一即放行 session.read.all(系统管理员)
 * 或 agent.lab.use。匿名兜底放行。</p>
 */
@Service
public class LabGoalRefineService {

    private static final Logger log = LoggerFactory.getLogger(LabGoalRefineService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> ALLOWED_PERMISSIONS =
            Set.of("session.read.all", "agent.lab.use");

    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;

    public LabGoalRefineService(LlmProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    public Mono<LabRefineGoalResponse> refine(LabRefineGoalRequest req) {
        // 权限检查:同步走完,SecurityContextHolder 是请求线程 ThreadLocal,这一步必须在
        // controller 调用栈上同步跑(随后的反应式链不再依赖 ThreadLocal)。
        requireLabAccess();
        if (req == null || req.userMessage() == null || req.userMessage().isBlank()) {
            return Mono.error(new IllegalArgumentException("userMessage 不能为空"));
        }

        String prompt = buildPrompt(req);
        return llmProvider.chatStream(new LlmChatRequest(req.llmModel(), req.llmConfigId(), prompt, java.util.List.of()))
                // 一边累积 token、一边检测 error 事件;遇到 error 直接抛
                .handle((LlmStreamEvent ev, reactor.core.publisher.SynchronousSink<String> sink) -> {
                    if ("error".equalsIgnoreCase(ev.type())) {
                        sink.error(new RuntimeException("refine-goal LLM error event: " + ev.content()));
                        return;
                    }
                    if ("token".equalsIgnoreCase(ev.type())) {
                        sink.next(ev.content() == null ? "" : ev.content());
                    }
                })
                .reduce("", (a, b) -> a + b)
                .timeout(CALL_TIMEOUT)
                .map(fullText -> parseLlmOutput(fullText, req))
                .onErrorMap(err -> err instanceof RuntimeException
                        ? err
                        : new RuntimeException("refine-goal LLM 调用失败:" + err.getMessage(), err));
    }

    private LabRefineGoalResponse parseLlmOutput(String fullText, LabRefineGoalRequest req) {
        if (fullText == null || fullText.isBlank()) {
            throw new RuntimeException("refine-goal LLM 返回空");
        }
        String jsonText = stripJsonFence(fullText).trim();
        int s = jsonText.indexOf('{');
        int e = jsonText.lastIndexOf('}');
        if (s >= 0 && e > s) {
            jsonText = jsonText.substring(s, e + 1);
        }
        try {
            JsonNode parsed = objectMapper.readTree(jsonText);
            String refined = parsed.path("refinedGoal").asText("");
            String assistantMessage = parsed.path("assistantMessage").asText("");
            if (refined.isBlank() && assistantMessage.isBlank()) {
                return new LabRefineGoalResponse(safe(req.draft()), fullText.trim());
            }
            if (refined.isBlank()) {
                refined = safe(req.draft());
            }
            return new LabRefineGoalResponse(refined, assistantMessage);
        } catch (Exception parseError) {
            log.warn("refine-goal LLM 输出非 JSON,fallback 到原文 raw={}", fullText);
            return new LabRefineGoalResponse(safe(req.draft()), fullText.trim());
        }
    }

    private String buildPrompt(LabRefineGoalRequest req) {
        StringBuilder history = new StringBuilder();
        if (req.history() != null) {
            for (LabRefineGoalRequest.Turn turn : req.history()) {
                if (turn == null) continue;
                String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
                history.append("[").append(role).append("] ").append(safe(turn.content())).append("\n");
            }
        }
        return """
                你是 Agent 实验室任务的"目标描述"协作助手。用户正在写一个 Agent 任务的目标描述,
                需要明确:输入是什么、期望输出是什么、可能用到哪些工具/方法、过程中应该做哪些操作。
                你帮用户把这段目标描述补充完整、表达更清晰、覆盖更全面。

                注意:
                - 你只完善"目标描述"本身,不去设计 Agent / Skill 结构。
                - 不要无中生有地增加业务约束,只在用户已表达的方向上补充。
                - 输出语言跟用户一致(中文进 → 中文出,英文进 → 英文出)。
                - refinedGoal 是<b>完整可直接用</b>的目标描述,不是 diff,不要写"在原描述基础上..."这种话。
                - assistantMessage 解释这一轮改/补了什么、为什么、还有哪些维度可以再问用户。

                严格输出一行 JSON,不要 markdown fence,不要任何额外文字:
                {"refinedGoal":"<完整新目标描述,可能多行,必须 JSON 转义>","assistantMessage":"<对用户的解释>"}

                ## 当前草稿(draft)
                %s

                ## 历史对话
                %s

                ## 用户本轮输入
                %s
                """.formatted(safe(req.draft()), history.toString(), safe(req.userMessage()));
    }

    private String stripJsonFence(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return t.substring(firstNl + 1, lastFence).trim();
            }
        }
        return t;
    }

    private String safe(String s) { return s == null ? "" : s; }

    private void requireLabAccess() {
        AuthPrincipal p = SecurityContextHolder.current();
        if (p == null) throw new AuthService.AuthException("forbidden", "missing principal");
        if (p.anonymous()) return;
        for (String perm : ALLOWED_PERMISSIONS) {
            if (p.permissions().contains(perm)) return;
        }
        throw new AuthService.AuthException("forbidden",
                "agent lab requires one of: " + ALLOWED_PERMISSIONS);
    }
}
