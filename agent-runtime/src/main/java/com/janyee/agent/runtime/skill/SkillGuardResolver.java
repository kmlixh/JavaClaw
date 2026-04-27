package com.janyee.agent.runtime.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a per-run {@link SkillGuard} by unioning constraints across the agent's active
 * skills.
 *
 * <p><b>Trigger-gated activation:</b> a skill whose {@code triggerKeywords} is non-empty
 * only contributes to the guard when the user's message literally contains one of them.
 * A skill with empty {@code triggerKeywords} follows the legacy always-active behavior —
 * i.e. it participates in every run for its bound agent.</p>
 *
 * <p>Rationale: without this gating, a skill like {@code skill.coverage.analysis} (which
 * declares required plan step IDs) will activate for every request to its agent, including
 * unrelated messages such as "who are you?". The LLM then emits a plain-text answer, the
 * run-end gate refuses to close the run because no plan was created, and the LLM spins
 * repeating itself until the nudge budget runs out. Trigger-gating eliminates that whole
 * failure mode.</p>
 *
 * <p>If no constraint qualifies the resolver returns {@link SkillGuard#NONE} and every
 * tool runs unconstrained.</p>
 */
@Component
public class SkillGuardResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillGuardResolver.class);

    private final SkillConstraintService constraintService;

    public SkillGuardResolver(SkillConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    /**
     * Legacy entry point — equivalent to {@link #resolve(String, String)} with a null
     * message, i.e. trigger-gated skills are treated as always-active. Kept so older
     * call sites (tests, resume paths without the original message) keep compiling.
     */
    public SkillGuard resolve(String agentId) {
        return resolve(agentId, null);
    }

    /**
     * Build the {@link SkillGuard} for this run. Skills with non-empty
     * {@code triggerKeywords} only contribute when {@code userMessage} contains at least
     * one of them (case-insensitive substring match). A null/blank {@code userMessage}
     * falls back to legacy behavior so resume flows don't accidentally drop the guard.
     */
    public SkillGuard resolve(String agentId, String userMessage) {
        if (agentId == null || agentId.isBlank()) {
            return SkillGuard.NONE;
        }
        List<SkillConstraint> constraints;
        try {
            constraints = constraintService.listActive(agentId);
        } catch (Exception error) {
            log.warn("skill.guard.resolve_failed agentId={}, error={}", agentId, error.getMessage());
            return SkillGuard.NONE;
        }
        if (constraints == null || constraints.isEmpty()) {
            return SkillGuard.NONE;
        }

        String lowerMessage = userMessage == null ? null : userMessage.toLowerCase(Locale.ROOT);

        Set<String> whitelist = new LinkedHashSet<>();
        Set<String> deniedSchemas = new LinkedHashSet<>();
        List<String> requiredPlanStepIds = new ArrayList<>();
        Map<String, PlanStepRule> stepRules = new LinkedHashMap<>();
        List<String> contributingSkills = new ArrayList<>();
        List<String> gatedOut = new ArrayList<>();

        for (SkillConstraint constraint : constraints) {
            if (constraint == null || !constraint.strict()) {
                continue;
            }
            if (!shouldActivate(constraint, lowerMessage)) {
                gatedOut.add(constraint.skillName());
                continue;
            }
            boolean contributed = false;
            for (String table : constraint.whitelistTables()) {
                if (table == null || table.isBlank()) {
                    continue;
                }
                String normalized = table.toLowerCase(Locale.ROOT).trim();
                if (!normalized.contains(".")) {
                    log.warn("skill.guard.whitelist_entry_missing_schema skill={}, entry={}",
                            constraint.skillName(), table);
                    continue;
                }
                whitelist.add(normalized);
                contributed = true;
            }
            // Multiple strict skills with different planStepIds would be a misconfiguration.
            // Take the first one that declares any — the LLM is meant to run one skill per run.
            if (requiredPlanStepIds.isEmpty() && !constraint.planStepIds().isEmpty()) {
                for (String step : constraint.planStepIds()) {
                    if (step != null && !step.isBlank()) {
                        requiredPlanStepIds.add(step.trim());
                        contributed = true;
                    }
                }
            }
            if (stepRules.isEmpty() && !constraint.stepRules().isEmpty()) {
                stepRules.putAll(constraint.stepRules());
                contributed = true;
            }
            if (contributed) {
                contributingSkills.add(constraint.skillName());
            }
        }

        if (whitelist.isEmpty() && requiredPlanStepIds.isEmpty()) {
            if (!gatedOut.isEmpty()) {
                log.info("skill.guard.all_gated_out agentId={}, triggerGatedSkills={}", agentId, gatedOut);
            }
            return SkillGuard.NONE;
        }
        SkillGuard guard = new SkillGuard(whitelist, deniedSchemas, requiredPlanStepIds, stepRules, contributingSkills);
        log.info("skill.guard.resolved agentId={}, whitelistSize={}, requiredStepIds={}, stepRuleIds={}, skills={}, gatedOut={}",
                agentId, guard.whitelistTables().size(), guard.requiredPlanStepIds(),
                stepRules.keySet(), contributingSkills, gatedOut);
        return guard;
    }

    private boolean shouldActivate(SkillConstraint constraint, String lowerMessage) {
        List<String> triggers = constraint.triggerKeywords();
        if (triggers.isEmpty()) {
            return true;
        }
        if (lowerMessage == null || lowerMessage.isBlank()) {
            // No message to match against → fall back to legacy always-active so resume
            // paths (which don't carry the original message) keep their guard.
            return true;
        }
        for (String keyword : triggers) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (lowerMessage.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
