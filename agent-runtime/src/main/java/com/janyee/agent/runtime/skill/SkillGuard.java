package com.janyee.agent.runtime.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enforcement view assembled from all active {@link SkillConstraint}s for a run. A guard
 * with {@link #hasTableEnforcement()} == false and no required plan step IDs is a no-op
 * and tools may proceed unchanged.
 *
 * Allowed tables are stored as lowercase `schema.table` so comparisons are case-insensitive.
 * Denied schemas cover schemas the LLM should never touch (information_schema, pg_catalog,
 * public) even if a whitelisted entry sits in the same DB.
 */
public final class SkillGuard {

    public static final SkillGuard NONE = new SkillGuard(
            Set.of(), Set.of(), List.of(), Map.of(), List.of()
    );

    private static final Set<String> DEFAULT_DENIED_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "public"
    );

    private final Set<String> whitelistTables;
    private final Set<String> deniedSchemas;
    private final List<String> requiredPlanStepIds;
    private final Map<String, PlanStepRule> stepRules;
    private final List<String> contributingSkills;

    public SkillGuard(
            Set<String> whitelistTables,
            Set<String> deniedSchemas,
            List<String> requiredPlanStepIds,
            Map<String, PlanStepRule> stepRules,
            List<String> contributingSkills
    ) {
        this.whitelistTables = whitelistTables == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(whitelistTables));
        this.deniedSchemas = deniedSchemas == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(deniedSchemas));
        this.requiredPlanStepIds = requiredPlanStepIds == null ? List.of()
                : List.copyOf(requiredPlanStepIds);
        this.stepRules = stepRules == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stepRules));
        this.contributingSkills = contributingSkills == null ? List.of()
                : List.copyOf(contributingSkills);
    }

    public Set<String> whitelistTables() {
        return whitelistTables;
    }

    public Set<String> deniedSchemas() {
        return deniedSchemas;
    }

    public List<String> requiredPlanStepIds() {
        return requiredPlanStepIds;
    }

    public Map<String, PlanStepRule> stepRules() {
        return stepRules;
    }

    /**
     * Resolve the effective rule for a step. If the step's rule declares
     * {@code reuseStep}, follow one link of indirection so callers can read "sector shares
     * completion with coverage" without duplicating declarations.
     *
     * <p>Use this for COMPLETION-criteria evaluation (requiresSuccess / minQueries /
     * requiredTables / acceptance). For per-step CONFIG (dependsOn / reportSection /
     * sqlTemplates) use {@link #stepRuleOwn(String)} so the step's own values are not
     * silently overwritten by the reuse target's.</p>
     */
    public Optional<PlanStepRule> stepRule(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        PlanStepRule rule = stepRules.get(stepId);
        if (rule == null) {
            return Optional.empty();
        }
        if (rule.reuseStep() != null && !rule.reuseStep().isBlank()) {
            PlanStepRule target = stepRules.get(rule.reuseStep());
            if (target != null) {
                return Optional.of(target);
            }
        }
        return Optional.of(rule);
    }

    /**
     * Return the step's OWN rule without following {@code reuseStep}. Use for
     * configuration that must reflect the step's identity (its own dependsOn,
     * reportSection heading, sqlTemplates). E.g. weak_grid declares its own
     * "## 4. 弱覆盖栅格分布" heading + dependsOn=["coverage"] while reusing
     * coverage's completion criteria — those values must NOT be replaced by
     * coverage's heading or dependsOn=[].
     */
    public Optional<PlanStepRule> stepRuleOwn(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stepRules.get(stepId));
    }

    public List<String> contributingSkills() {
        return contributingSkills;
    }

    /**
     * Artifact tools explicitly whitelisted by the active skills, derived from the
     * {@code requiresSuccess} lists on {@link PlanStepRule}s. If non-empty, {@code artifact.*}
     * tool calls outside this set must be rejected — otherwise the LLM might generate a
     * .word/.pptx/.xlsx when the skill demands a .md deliverable.
     */
    public Set<String> allowedArtifactTools() {
        Set<String> result = new LinkedHashSet<>();
        for (PlanStepRule rule : stepRules.values()) {
            if (rule == null) {
                continue;
            }
            for (String tool : rule.requiresSuccess()) {
                if (tool != null && tool.startsWith("artifact.")) {
                    result.add(tool);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean hasTableEnforcement() {
        return !whitelistTables.isEmpty();
    }

    public boolean hasPlanEnforcement() {
        return !requiredPlanStepIds.isEmpty();
    }

    public boolean isEmpty() {
        return !hasTableEnforcement() && !hasPlanEnforcement();
    }

    /**
     * True when a schema is on the hard-deny list (information_schema, pg_catalog, public)
     * OR on the configured deniedSchemas set. Intentionally permissive on blank/null so
     * callers don't have to null-guard.
     */
    public boolean isSchemaDenied(String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        String lower = schema.toLowerCase(Locale.ROOT).trim();
        return DEFAULT_DENIED_SCHEMAS.contains(lower) || deniedSchemas.contains(lower);
    }

    /**
     * Evaluates one referenced table against the whitelist. A table is allowed when:
     *   - whitelist is empty (no enforcement), or
     *   - its normalized `schema.table` appears in the whitelist AND its schema is not denied.
     * Entries without a schema are rejected under hard enforcement because the caller must
     * be explicit.
     */
    public TableCheck checkTable(String schema, String table) {
        if (!hasTableEnforcement()) {
            return TableCheck.allow();
        }
        if (table == null || table.isBlank()) {
            return TableCheck.reject("empty table identifier");
        }
        String normSchema = schema == null ? "" : schema.toLowerCase(Locale.ROOT).trim();
        String normTable = table.toLowerCase(Locale.ROOT).trim();
        if (normSchema.isEmpty()) {
            return TableCheck.reject(
                    "table '" + table + "' is not schema-qualified; whitelist requires schema.table"
            );
        }
        if (isSchemaDenied(normSchema)) {
            return TableCheck.reject(
                    "schema '" + normSchema + "' is on the hard-deny list (information_schema / pg_catalog / public)"
            );
        }
        String qualified = normSchema + "." + normTable;
        if (!whitelistTables.contains(qualified)) {
            return TableCheck.reject(
                    "table '" + qualified + "' is not in the skill whitelist"
            );
        }
        return TableCheck.allow();
    }

    public boolean matchesRequiredPlanStepIds(List<String> proposed) {
        if (requiredPlanStepIds.isEmpty()) {
            return true;
        }
        if (proposed == null || proposed.size() != requiredPlanStepIds.size()) {
            return false;
        }
        for (int i = 0; i < requiredPlanStepIds.size(); i++) {
            String expected = requiredPlanStepIds.get(i);
            String actual = proposed.get(i);
            if (expected == null || actual == null) {
                return false;
            }
            if (!expected.equalsIgnoreCase(actual.trim())) {
                return false;
            }
        }
        return true;
    }

    public record TableCheck(boolean allowed, String reason) {
        public static TableCheck allow() {
            return new TableCheck(true, null);
        }
        public static TableCheck reject(String reason) {
            return new TableCheck(false, reason);
        }
    }
}
