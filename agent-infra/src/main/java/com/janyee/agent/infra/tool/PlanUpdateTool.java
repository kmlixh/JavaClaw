package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.janyee.agent.runtime.loop.NextActionHint;
import com.janyee.agent.runtime.loop.PlanStatus;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.loop.RunPlanStore;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.infra.run.RunPlanPersister;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.PlanStepRuleEvaluator;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.runtime.skill.SkillGuardStore;
import com.janyee.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PlanUpdateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlanUpdateTool.class);

    private final RunPlanStore planStore;
    private final SkillGuardStore guardStore;
    private final ObjectMapper objectMapper;
    private final RunPlanPersister planPersister;

    public PlanUpdateTool(RunPlanStore planStore, SkillGuardStore guardStore, ObjectMapper objectMapper, RunPlanPersister planPersister) {
        this.planStore = planStore;
        this.guardStore = guardStore;
        this.objectMapper = objectMapper;
        this.planPersister = planPersister;
    }

    @Override
    public String name() {
        return "plan.update";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Advance an existing plan step: mark IN_PROGRESS before running the underlying tool(s), then COMPLETED / FAILED / SKIPPED once the step's expected output is produced. Supply resultNote when closing a step so later iterations can see what was done.",
                """
                {"type":"object","properties":{
                  "stepId":{"type":"string","description":"Id of the step to update, as declared by plan.create"},
                  "status":{"type":"string","enum":["PENDING","IN_PROGRESS","COMPLETED","FAILED","SKIPPED"]},
                  "resultNote":{"type":"string","description":"Short human note recording what happened when closing the step"},
                  "toolHint":{"type":"string","description":"Optional updated tool hint"},
                  "expectedOutput":{"type":"string","description":"Optional updated expectedOutput"}
                },"required":["stepId","status"]}
                """
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            RunPlan plan = planStore.find(invocation.runId()).orElse(null);
            if (plan == null) {
                return new ToolResult(false, "plan store not initialized for run",
                        "{}", "[]", "no plan registered for runId=" + invocation.runId());
            }
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String stepId = args.path("stepId").asText("");
            String statusRaw = args.path("status").asText("");
            if (stepId.isBlank() || statusRaw.isBlank()) {
                return new ToolResult(false, "stepId and status are required",
                        "{}", "[]", "plan.update requires stepId and status");
            }
            PlanStep step = plan.find(stepId).orElse(null);
            if (step == null) {
                return new ToolResult(false, "unknown stepId",
                        "{}", "[]", "no such step: " + stepId);
            }
            PlanStatus nextStatus;
            try {
                nextStatus = PlanStatus.valueOf(statusRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notEnum) {
                return new ToolResult(false, "invalid status",
                        "{}", "[]", "status must be one of PENDING/IN_PROGRESS/COMPLETED/FAILED/SKIPPED");
            }
            // Wave barrier:转 IN_PROGRESS 之前,所有声明的 dependsOn step 必须已经 COMPLETED 或 SKIPPED。
            // 这堵住"LLM 跳过 PENDING 兄弟 step 直接做下一波"的漏洞 —— 也是用户明确要求的"并行 step
            // 全部完成才能下一步"。SKIPPED 也算 OK,因为 skill 允许某些 step 在 zeroRowsAllowed=false
            // 等场景下直接跳过(例如某城市没有 5G 数据,5G step 标 SKIPPED 后续不该被它阻塞)。
            if (nextStatus == PlanStatus.IN_PROGRESS && step.status() != PlanStatus.IN_PROGRESS) {
                List<String> unmet = plan.unmetDependencies(step);
                if (!unmet.isEmpty()) {
                    String reason = "step '" + step.id()
                            + "' cannot start: waiting on dependencies " + unmet
                            + ". Finish each predecessor (mark COMPLETED via plan.update) "
                            + "before advancing this step.";
                    log.warn("plan.update.dependency_unmet runId={}, stepId={}, unmet={}",
                            invocation.runId(), step.id(), unmet);
                    return new ToolResult(false, "step blocked by dependencies",
                            "{}", "[]", reason);
                }
            }
            if (nextStatus == PlanStatus.COMPLETED) {
                String completionError = validateCompletion(step);
                if (completionError != null) {
                    String message = completionError + " " + NextActionHint.forCompletionRejection(step);
                    return new ToolResult(false, "step completion rejected",
                            "{}", "[]", message);
                }
                SkillGuard guard = guardStore.find(invocation.runId()).orElse(SkillGuard.NONE);
                if (!guard.isEmpty()) {
                    PlanStepRule rule = guard.stepRule(step.id()).orElse(null);
                    if (rule != null && !rule.isEmpty()) {
                        String artifactContent = latestArtifactContent(plan, step);
                        List<String> violations = PlanStepRuleEvaluator.evaluateCompletion(step, rule, artifactContent);
                        if (!violations.isEmpty()) {
                            String message = "step '" + step.id()
                                    + "' cannot be marked COMPLETED yet: "
                                    + String.join("; ", violations)
                                    + ". " + NextActionHint.forCompletionRejection(step);
                            log.warn("plan.update.rule_violation runId={}, stepId={}, violations={}",
                                    invocation.runId(), step.id(), violations);
                            return new ToolResult(false, "step completion rejected",
                                    "{}", "[]", message);
                        }
                    }
                }
            }
            step.updateStatus(nextStatus);
            if (args.hasNonNull("resultNote")) {
                step.updateResultNote(args.path("resultNote").asText(""));
            }
            if (args.hasNonNull("toolHint")) {
                step.updateToolHint(args.path("toolHint").asText(""));
            }
            if (args.hasNonNull("expectedOutput")) {
                step.updateExpectedOutput(args.path("expectedOutput").asText(""));
            }
            log.info("plan.update runId={}, stepId={}, status={}", invocation.runId(), stepId, nextStatus);
            planPersister.sync(invocation.runId(), plan);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("displayType", "plan");
            data.put("stepId", stepId);
            data.put("status", nextStatus.name());
            data.put("rendered", plan.renderCompact());
            return new ToolResult(
                    true,
                    "step " + stepId + " -> " + nextStatus,
                    objectMapper.writeValueAsString(data),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "plan.update failed", "{}", "[]", error.getMessage());
        }
    }

    /**
     * The artifact body captured by DefaultToolResultAppender when an artifact.* tool
     * succeeded while this step was IN_PROGRESS. Returns null when nothing has been stored
     * so the evaluator can distinguish "no artifact yet" from "artifact missing heading".
     */
    private String latestArtifactContent(RunPlan plan, PlanStep step) {
        String content = step.artifactContent();
        return content == null || content.isBlank() ? null : content;
    }

    /**
     * If the step declares a deliverable tool (anything under the artifact.* namespace) via
     * its toolHint, refuse to mark it COMPLETED until a successful invocation of that tool
     * has actually landed a summary on the step. This prevents the LLM from faking "report
     * generated" without calling artifact.markdown / artifact.word / etc.
     */
    private String validateCompletion(PlanStep step) {
        String hint = step.toolHint();
        if (hint == null || !hint.toLowerCase(Locale.ROOT).contains("artifact.")) {
            return null;
        }
        boolean hasArtifactSummary = step.toolSummaries().stream()
                .anyMatch(summary -> summary.toolName() != null
                        && summary.toolName().startsWith("artifact."));
        if (hasArtifactSummary) {
            return null;
        }
        return "cannot mark '" + step.id() + "' COMPLETED: its toolHint promises an artifact.* "
                + "deliverable but no successful artifact.* tool call has been recorded for this step. "
                + "Invoke the artifact tool (e.g. artifact.markdown) first, then retry plan.update.";
    }
}
