package com.janyee.agent.runtime.skill;

import java.util.List;

/**
 * Declarative completion criteria + materialized context for one plan step, authored in
 * a skill's {@code config_json.planStepRules[<stepId>]}. The orchestrator, PlanUpdateTool,
 * PlanCreateTool, and PlanStepRuleEvaluator all consult the rule so a skill author can
 * declare once — in config — what each step does, what tables it touches, what SQL to use,
 * and where it lands in the final report, without the LLM having to retain it all in prompt.
 *
 * All fields are optional; a rule with every field empty is effectively a no-op.
 *
 * <h3>Completion criteria</h3>
 * <ul>
 *   <li>{@code requiresSuccess} — at least one listed tool must have a successful invocation
 *       recorded on this step's toolSummaries.
 *   <li>{@code minQueries} — minimum number of successful {@code db.query} calls.
 *   <li>{@code zeroRowsAllowed} — whether a db.query returning 0 rows counts toward completion.
 *   <li>{@code reuseStep} — this step inherits completion criteria from another (e.g.
 *       weak_grid reuses coverage because the same SQL answers both).
 * </ul>
 *
 * <h3>Materialization for LLM</h3>
 * <ul>
 *   <li>{@code tableAllowList} — subset of the skill-level whitelist authorized for this step.
 *   <li>{@code sqlTemplates} — canonical SQL snippets the LLM is expected to use verbatim
 *       (after variable substitution like {@code {{city}}}, {@code {{county}}}).
 *   <li>{@code reportSection} — the step's slot in the final markdown (heading text + expected
 *       placeholder descriptors).
 * </ul>
 */
public record PlanStepRule(
        List<String> requiresSuccess,
        List<String> tableAllowList,
        int minQueries,
        boolean zeroRowsAllowed,
        List<String> mustMatchTemplateAnchors,
        String reuseStep,
        List<String> sqlTemplates,
        List<String> sqlTemplatesGeoJson,
        List<String> sqlTemplatesNoFilter,
        ReportSection reportSection,
        String jdbcUrl,
        /**
         * Fully-qualified {@code schema.table} names that MUST each have been touched by at
         * least one successful {@code db.query} before this step may transition to COMPLETED.
         *
         * <p>Motivation: {@code minQueries=1} is too lax for business steps that need N
         * independent data sources — e.g. {@code sector} requires 2G/4G/5G/buildings (4 tables),
         * {@code weak_area} requires OTT5G/OTT4G/MDT4G (3 tables). Without this field the LLM
         * can query one table, fabricate the rest, and still pass the rule.</p>
         */
        List<String> requiredTables
) {
    public PlanStepRule {
        requiresSuccess = requiresSuccess == null ? List.of() : List.copyOf(requiresSuccess);
        tableAllowList = tableAllowList == null ? List.of() : List.copyOf(tableAllowList);
        mustMatchTemplateAnchors = mustMatchTemplateAnchors == null ? List.of() : List.copyOf(mustMatchTemplateAnchors);
        if (minQueries < 0) {
            minQueries = 0;
        }
        reuseStep = reuseStep == null ? "" : reuseStep.trim();
        sqlTemplates = sqlTemplates == null ? List.of() : List.copyOf(sqlTemplates);
        sqlTemplatesGeoJson = sqlTemplatesGeoJson == null ? List.of() : List.copyOf(sqlTemplatesGeoJson);
        sqlTemplatesNoFilter = sqlTemplatesNoFilter == null ? List.of() : List.copyOf(sqlTemplatesNoFilter);
        jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        requiredTables = requiredTables == null ? List.of() : List.copyOf(requiredTables);
    }

    public static PlanStepRule empty() {
        return new PlanStepRule(List.of(), List.of(), 0, true, List.of(), "", List.of(), List.of(), List.of(), null, "", List.of());
    }

    public boolean isEmpty() {
        return requiresSuccess.isEmpty()
                && tableAllowList.isEmpty()
                && minQueries == 0
                && mustMatchTemplateAnchors.isEmpty()
                && reuseStep.isEmpty()
                && sqlTemplates.isEmpty()
                && sqlTemplatesGeoJson.isEmpty()
                && sqlTemplatesNoFilter.isEmpty()
                && reportSection == null
                && jdbcUrl.isEmpty()
                && requiredTables.isEmpty();
    }

    /**
     * Declarative slot in the final markdown artifact. {@code heading} is the exact line
     * (e.g. {@code "## 1. 区域扇区概况"}) that must appear in the artifact content.
     * {@code placeholders} describe each bullet/cell the LLM needs to fill (purely
     * informational — not validated).
     */
    public record ReportSection(String heading, List<Placeholder> placeholders) {
        public ReportSection {
            heading = heading == null ? "" : heading.trim();
            placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
        }
    }

    public record Placeholder(String label, String source) {
        public Placeholder {
            label = label == null ? "" : label.trim();
            source = source == null ? "" : source.trim();
        }
    }
}
