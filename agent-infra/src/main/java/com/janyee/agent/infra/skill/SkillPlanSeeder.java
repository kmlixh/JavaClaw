package com.janyee.agent.infra.skill;

import com.janyee.agent.infra.run.RunPlanPersister;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.SkillGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Auto-seeds the RunPlan from a SkillGuard's declared {@code requiredPlanStepIds} + per-step
 * {@link PlanStepRule}s. Called at run startup (after guard resolution) so the LLM no longer
 * has to call {@code plan.create} with the right step IDs — the plan is already there,
 * PENDING, ready for {@code plan.update}.
 *
 * <p>This implements the "feedback_enforcement_over_prompt" policy: stop relying on the LLM
 * to remember to call plan.create with the exact step IDs, because it sometimes skips that
 * step entirely and then gets blocked at artifact time with a cryptic "skill requires a plan
 * with steps ..." error.
 */
@Component
public class SkillPlanSeeder {

    private static final Logger log = LoggerFactory.getLogger(SkillPlanSeeder.class);

    private final RunPlanPersister planPersister;

    public SkillPlanSeeder(RunPlanPersister planPersister) {
        this.planPersister = planPersister;
    }

    /**
     * If the guard declares required plan step IDs and the plan is still empty, seed it.
     * Idempotent: calling twice on a non-empty plan is a no-op. Returns {@code true} when
     * seeding actually happened.
     */
    public boolean seedIfNeeded(String runId, RunPlan plan, SkillGuard guard) {
        if (plan == null || guard == null || !guard.hasPlanEnforcement()) {
            return false;
        }
        if (!plan.isEmpty()) {
            return false; // LLM already called plan.create; leave it alone
        }
        String previousStepId = null;
        for (String stepId : guard.requiredPlanStepIds()) {
            // 配置/装配场景用 own 规则:不跟随 reuseStep,确保 weak_grid 这种"借用 coverage
            // 完成校验"的 step 仍能保留自己的 dependsOn / reportSection / title 派生源。
            // 完成校验另走 stepRule()(仍跟随 reuseStep)。
            PlanStepRule ownRule = guard.stepRuleOwn(stepId).orElse(null);
            String title = deriveTitle(stepId, ownRule);
            String toolHint = deriveToolHint(stepId, ownRule);
            String expectedOutput = deriveExpectedOutput(ownRule);
            PlanStep step = new PlanStep(stepId, title, toolHint, expectedOutput);
            materializeFromRule(step, guard);
            // 默认串行兜底:skill 没在 JSON 里写 dependsOn,就让当前 step 串行依赖前一个 step,
            // 老 skill 不用动也能拿到"先 A 完再 B"的安全行为。Skill 想要并行的话,在 JSON 里
            // 显式声明 dependsOn(可以是空数组,可以是更早的某个 step),覆盖这个默认值。
            applyDependsOnFallback(step, ownRule, previousStepId);
            plan.addStep(step);
            previousStepId = stepId;
        }
        // Keep title empty — skill author can override later if they want, but a blank title
        // is better than a generic "Auto-seeded plan" that pretends to describe intent.
        log.info("skill.plan.auto_seeded runId={}, stepIds={}, contributingSkills={}",
                runId, guard.requiredPlanStepIds(), guard.contributingSkills());
        planPersister.sync(runId, plan);
        return true;
    }

    private String deriveTitle(String stepId, PlanStepRule rule) {
        if (rule != null && rule.reportSection() != null) {
            String heading = rule.reportSection().heading();
            if (heading != null && !heading.isBlank()) {
                // Strip markdown "## " prefix, that's a report concern not a plan concern.
                return heading.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return stepId;
    }

    /**
     * Best-effort hint: first entry of {@code requiresSuccess}, or a tool name derived from
     * the stepId's position. Absence is fine — LLM will figure it out from the SQL templates
     * materialized on the step.
     */
    private String deriveToolHint(String stepId, PlanStepRule rule) {
        if (rule != null && !rule.requiresSuccess().isEmpty()) {
            return rule.requiresSuccess().get(0);
        }
        // Conservative default: non-report data steps use db.query; the synthesis step uses
        // artifact.markdown.
        if ("report".equalsIgnoreCase(stepId)) {
            return "artifact.markdown";
        }
        return "db.query";
    }

    private String deriveExpectedOutput(PlanStepRule rule) {
        if (rule == null || rule.reportSection() == null) {
            return "";
        }
        PlanStepRule.ReportSection section = rule.reportSection();
        if (section.placeholders().isEmpty()) {
            return section.heading();
        }
        return section.placeholders().stream()
                .map(p -> p.source().isEmpty() ? p.label() : p.label() + " <- " + p.source())
                .collect(Collectors.joining("; "));
    }

    /**
     * 默认串行兜底:rule 没声明 dependsOn(dependsOnDeclared=false)且当前 step 不是第一个,
     * 给它打上 [previousStepId] 这个隐式依赖。声明了 dependsOn 的 rule —— 哪怕是空数组 ——
     * 都按 rule 自身值走,这是 skill 作者明确的"我是根 step"或"我依赖 A、B"的意思。
     */
    private void applyDependsOnFallback(PlanStep step, PlanStepRule rule, String previousStepId) {
        if (rule != null && rule.dependsOnDeclared()) {
            step.setDependsOn(rule.dependsOn());
            return;
        }
        if (previousStepId != null) {
            step.setDependsOn(java.util.List.of(previousStepId));
        }
    }

    /**
     * Mirrors {@code PlanCreateTool.materializeFromRule} — copies the skill's per-step rule
     * details onto the freshly created PlanStep so the rendered plan carries SQL templates /
     * allowed tables / report heading directly, without the LLM having to dig through prompt
     * text to find them.
     */
    private void materializeFromRule(PlanStep step, SkillGuard guard) {
        // 用 OWN 规则物化:reuseStep 只为了共享完成校验,不该把 weak_grid 自己的 reportSection
        // / sqlTemplates / dependsOn 替换成 coverage 的。
        PlanStepRule rule = guard.stepRuleOwn(step.id()).orElse(null);
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
}
