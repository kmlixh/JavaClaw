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
        for (String stepId : guard.requiredPlanStepIds()) {
            PlanStepRule rule = guard.stepRule(stepId).orElse(null);
            String title = deriveTitle(stepId, rule);
            String toolHint = deriveToolHint(stepId, rule);
            String expectedOutput = deriveExpectedOutput(rule);
            PlanStep step = new PlanStep(stepId, title, toolHint, expectedOutput);
            materializeFromRule(step, guard);
            plan.addStep(step);
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
     * Mirrors {@code PlanCreateTool.materializeFromRule} — copies the skill's per-step rule
     * details onto the freshly created PlanStep so the rendered plan carries SQL templates /
     * allowed tables / report heading directly, without the LLM having to dig through prompt
     * text to find them.
     */
    private void materializeFromRule(PlanStep step, SkillGuard guard) {
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
}
