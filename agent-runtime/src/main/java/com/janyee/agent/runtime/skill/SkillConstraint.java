package com.janyee.agent.runtime.skill;

import java.util.List;
import java.util.Map;

/**
 * Structured constraints parsed from a skill's `config_json`. Populated by the infra layer
 * and consumed by {@link SkillGuardResolver} to build an aggregate {@link SkillGuard}.
 *
 * - `whitelistTables` entries are fully qualified `schema.table`, lowercase.
 * - `planStepIds` is an ordered list; if non-empty, plan.create steps must match exactly.
 * - `stepRules` optionally declares per-step completion criteria keyed by step id.
 * - `strict` means this constraint participates in hard enforcement.
 * - `triggerKeywords` (optional): if non-empty, this skill only activates when the user
 *   message literally contains one of them. Empty list = legacy always-active behavior.
 */
public record SkillConstraint(
        String skillName,
        List<String> whitelistTables,
        List<String> planStepIds,
        Map<String, PlanStepRule> stepRules,
        boolean strict,
        List<String> triggerKeywords
) {
    public SkillConstraint {
        whitelistTables = whitelistTables == null ? List.of() : List.copyOf(whitelistTables);
        planStepIds = planStepIds == null ? List.of() : List.copyOf(planStepIds);
        stepRules = stepRules == null ? Map.of() : Map.copyOf(stepRules);
        triggerKeywords = triggerKeywords == null ? List.of() : List.copyOf(triggerKeywords);
    }

    public SkillConstraint(
            String skillName,
            List<String> whitelistTables,
            List<String> planStepIds,
            Map<String, PlanStepRule> stepRules,
            boolean strict
    ) {
        this(skillName, whitelistTables, planStepIds, stepRules, strict, List.of());
    }
}
