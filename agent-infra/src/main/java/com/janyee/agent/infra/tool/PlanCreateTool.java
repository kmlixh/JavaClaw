package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.loop.NextActionHint;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.loop.RunPlanStore;
import com.janyee.agent.infra.run.RunPlanPersister;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.runtime.skill.SkillGuardStore;
import com.janyee.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlanCreateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlanCreateTool.class);

    private final RunPlanStore planStore;
    private final SkillGuardStore guardStore;
    private final ObjectMapper objectMapper;
    private final RunPlanPersister planPersister;

    public PlanCreateTool(RunPlanStore planStore, SkillGuardStore guardStore, ObjectMapper objectMapper, RunPlanPersister planPersister) {
        this.planStore = planStore;
        this.guardStore = guardStore;
        this.objectMapper = objectMapper;
        this.planPersister = planPersister;
    }

    @Override
    public String name() {
        return "plan.create";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Register the run's execution plan on first iteration. Each step describes one analysis dimension or deliverable; later iterations advance through them with plan.update. Do not use for ad-hoc to-do lists in chat text.",
                """
                {"type":"object","properties":{
                  "title":{"type":"string","description":"Overall goal of the plan"},
                  "steps":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string","description":"Short unique id, e.g. step-1, sector-5g"},
                    "title":{"type":"string","description":"Human readable description of the step"},
                    "toolHint":{"type":"string","description":"Tool(s) expected to drive this step, e.g. db.query or artifact.markdown"},
                    "expectedOutput":{"type":"string","description":"What this step must produce before being marked COMPLETED"}
                  },"required":["id","title"]}}
                },"required":["steps"]}
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
            if (!plan.isEmpty()) {
                // Idempotent: return the current plan snapshot so the LLM doesn't keep retrying.
                // 顺带把"下一步到底该干嘛"拼成具体 tool 调用模板塞进 summary —— 避免 LLM 又从头再来一次。
                String nextHint = NextActionHint.suggest(plan).orElse(
                        "all steps appear COMPLETED; produce your final assistant text and stop.");
                Map<String, Object> existing = new LinkedHashMap<>();
                existing.put("displayType", "plan");
                existing.put("alreadyCreated", true);
                existing.put("title", plan.title());
                existing.put("stepCount", plan.size());
                existing.put("rendered", plan.renderCompact());
                existing.put("snapshot", plan.toSnapshot());
                existing.put("nextAction", nextHint);
                return new ToolResult(
                        true,
                        "plan already exists with " + plan.size() + " steps. " + nextHint,
                        objectMapper.writeValueAsString(existing),
                        "[]",
                        null
                );
            }
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String title = args.path("title").asText("");
            JsonNode stepsNode = args.path("steps");
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                return new ToolResult(false, "steps array is required",
                        "{}", "[]", "plan.create requires at least one step");
            }
            List<String> proposedStepIds = new ArrayList<>(stepsNode.size());
            for (JsonNode s : stepsNode) {
                proposedStepIds.add(s.path("id").asText(""));
            }
            SkillGuard guard = guardStore.find(invocation.runId()).orElse(SkillGuard.NONE);

            // 观察：LLM 第一次 plan.create 经常用通用命名 step-1/step-2/...，和 skill 要求的
            //      sector/weak_area/coverage/weak_grid/report 不一致 —— 但**意图是对的**，
            //      只是按序规划了正确数量的步骤。这种情况下按 position 自动改名成 skill 要求的
            //      id，比一口回绝让 LLM 再来一轮划算得多（每个 run 本来都要浪费 1 次 iteration）。
            //
            //      仅数量匹配才做自动改名；数量不匹配（比如 LLM 规划了 8 步 vs skill 要 5 步）
            //      说明 LLM 对任务分解的理解本身就错了，必须让它重新规划。
            boolean autoRenamed = false;
            if (guard.hasPlanEnforcement()
                    && !guard.matchesRequiredPlanStepIds(proposedStepIds)
                    && proposedStepIds.size() == guard.requiredPlanStepIds().size()) {
                proposedStepIds = List.copyOf(guard.requiredPlanStepIds());
                autoRenamed = true;
                log.info("plan.create.auto_rename runId={}, to={}",
                        invocation.runId(), proposedStepIds);
            }
            if (guard.hasPlanEnforcement() && !guard.matchesRequiredPlanStepIds(proposedStepIds)) {
                return rejectPlanStepIds(invocation.runId(), guard, proposedStepIds);
            }

            plan.setTitle(title);
            int idx = 0;
            for (JsonNode s : stepsNode) {
                String id = autoRenamed ? proposedStepIds.get(idx) : s.path("id").asText("");
                String stepTitle = s.path("title").asText("");
                String toolHint = s.path("toolHint").asText("");
                String expectedOutput = s.path("expectedOutput").asText("");
                PlanStep step = new PlanStep(id, stepTitle, toolHint, expectedOutput);
                materializeFromRule(step, guard);
                plan.addStep(step);
                idx++;
            }
            log.info("plan.create runId={}, steps={}, autoRenamed={}",
                    invocation.runId(), plan.size(), autoRenamed);
            planPersister.sync(invocation.runId(), plan);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("displayType", "plan");
            data.put("title", plan.title());
            data.put("stepCount", plan.size());
            data.put("rendered", plan.renderCompact());
            if (autoRenamed) {
                data.put("autoRenamedStepIds", proposedStepIds);
            }
            String summary = autoRenamed
                    ? ("plan created with " + plan.size() + " steps (step ids auto-renamed to "
                        + proposedStepIds + " to match the active skill; use these ids for plan.update)")
                    : ("plan created with " + plan.size() + " steps");
            return new ToolResult(
                    true,
                    summary,
                    objectMapper.writeValueAsString(data),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "plan.create failed", "{}", "[]", error.getMessage());
        }
    }

    /**
     * Copy the skill's per-step rule details (tableAllowList, sqlTemplates, reportSection)
     * onto the freshly created PlanStep. This way the LLM sees "which tables / which SQL /
     * which heading" directly in the rendered plan, instead of having to remember skill
     * prompt text under context pressure.
     */
    private void materializeFromRule(PlanStep step, SkillGuard guard) {
        if (guard == null || guard.isEmpty()) {
            return;
        }
        PlanStepRule rule = guard.stepRule(step.id()).orElse(null);
        if (rule == null || rule.isEmpty()) {
            return;
        }
        if (!rule.tableAllowList().isEmpty()) {
            step.setAllowedTables(rule.tableAllowList());
        }
        if (!rule.sqlTemplates().isEmpty()) {
            step.setSqlTemplates(rule.sqlTemplates());
        }
        if (!rule.sqlTemplatesGeoJson().isEmpty()) {
            step.setSqlTemplatesGeoJson(rule.sqlTemplatesGeoJson());
        }
        if (!rule.sqlTemplatesNoFilter().isEmpty()) {
            step.setSqlTemplatesNoFilter(rule.sqlTemplatesNoFilter());
        }
        if (rule.jdbcUrl() != null && !rule.jdbcUrl().isBlank()) {
            step.setJdbcUrl(rule.jdbcUrl());
        }
        PlanStepRule.ReportSection section = rule.reportSection();
        if (section != null) {
            step.setReportHeading(section.heading());
            if (!section.placeholders().isEmpty()) {
                step.setReportPlaceholders(section.placeholders().stream()
                        .map(p -> p.source().isEmpty() ? p.label() : p.label() + " <- " + p.source())
                        .collect(Collectors.toList()));
            }
        }
    }

    private ToolResult rejectPlanStepIds(String runId, SkillGuard guard, List<String> proposed) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("displayType", "tool_error");
            data.put("tool", name());
            data.put("recoverable", true);
            data.put("retryRecommended", true);
            data.put("reason", "plan_step_ids_mismatch");
            data.put("requiredStepIds", guard.requiredPlanStepIds());
            data.put("receivedStepIds", proposed);
            data.put("skills", guard.contributingSkills());
            String message = "plan step IDs must be exactly "
                    + guard.requiredPlanStepIds()
                    + " in that order. Received " + proposed
                    + ". Recreate the plan with ids matching the active skill.";
            data.put("message", message);
            log.warn("plan.create.rejected runId={}, required={}, received={}",
                    runId, guard.requiredPlanStepIds(), proposed);
            return new ToolResult(
                    false,
                    "plan step IDs do not match the active skill",
                    objectMapper.writeValueAsString(data),
                    "[]",
                    message
            );
        } catch (Exception error) {
            return new ToolResult(false, "plan.create step IDs mismatch",
                    "{}", "[]", error.getMessage());
        }
    }
}
