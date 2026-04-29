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
        List<String> requiredTables,
        /**
         * Step IDs that must be COMPLETED (or SKIPPED) before this step may transition to
         * IN_PROGRESS. Empty list means "no explicit dependency" — see {@link #dependsOnDeclared}
         * for the semantic distinction between declared-empty and not-declared.
         *
         * <p>Drives wave-barriered execution: parallel siblings (steps that share a common
         * dependency root) can run concurrently, but a downstream step waits for *all* declared
         * predecessors. Without this, the LLM can mark the report step IN_PROGRESS while half
         * the data steps are still PENDING, then write a partial report.</p>
         */
        List<String> dependsOn,
        /**
         * True iff the skill's JSON explicitly set the {@code dependsOn} key (even to empty
         * array). When false, the seeder falls back to "serial after previous step" — the
         * conservative default for old skills that pre-date this feature.
         */
        boolean dependsOnDeclared,
        /**
         * Acceptance criteria for the step's data outputs. Validated by
         * {@link PlanStepRuleEvaluator} against each db.query summary's parsed first row.
         * Empty/null means "no data-level acceptance check" — the existing requiresSuccess /
         * minQueries / requiredTables rules still apply on their own.
         *
         * <p>Motivation: requiredTables only checks the LLM ran a query on the right table;
         * it doesn't catch "query returned cnt=0 for every cell type". Acceptance lets the
         * skill author declare "the region must have *some* data here, otherwise skip the
         * step rather than write zeros into the report".</p>
         */
        Acceptance acceptance
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
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        acceptance = acceptance == null ? Acceptance.NONE : acceptance;
    }

    public static PlanStepRule empty() {
        return new PlanStepRule(List.of(), List.of(), 0, true, List.of(), "", List.of(), List.of(), List.of(), null, "", List.of(), List.of(), false, Acceptance.NONE);
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
                && requiredTables.isEmpty()
                && dependsOn.isEmpty()
                && !dependsOnDeclared
                && acceptance.isEmpty();
    }

    /**
     * Per-step data-level acceptance criteria evaluated against the step's accumulated
     * {@link com.janyee.agent.runtime.loop.CompletedToolSummary} list. All checks are
     * evaluated; a step's transition to COMPLETED is rejected if any fails. Designed to
     * surface common LLM lies / preflight scope mismatches:
     *
     * <ul>
     *   <li>{@code requiredColumns} — every name must appear as a key in at least one
     *       summary's firstRowJson. Catches "queried the right table but selected the wrong
     *       column" — e.g. SELECT count(*) when the report expects cnt_2g/cnt_4g/cnt_5g.</li>
     *   <li>{@code requireNonZeroData} — across ALL summaries' firstRowJson values, at least
     *       one numeric value must be greater than zero. Catches "every aggregate returned 0"
     *       which usually means the region filter produced an empty result set; LLM should
     *       SKIP the step or re-query, not propagate zeros into the artifact.</li>
     * </ul>
     *
     * <p>Both checks only consult successful {@code db.query} summaries. Steps whose
     * requiresSuccess is artifact.* (e.g. report) are never subjected to these checks —
     * their acceptance is governed by {@code reportSection} heading + placeholder anchors
     * which already exist in the evaluator.</p>
     */
    public record Acceptance(
            List<String> requiredColumns,
            boolean requireNonZeroData
    ) {
        public static final Acceptance NONE = new Acceptance(List.of(), false);

        public Acceptance {
            requiredColumns = requiredColumns == null ? List.of() : List.copyOf(requiredColumns);
        }

        public boolean isEmpty() {
            return requiredColumns.isEmpty() && !requireNonZeroData;
        }
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
