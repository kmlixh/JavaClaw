package com.janyee.agent.runtime.skill;

import com.janyee.agent.runtime.loop.CompletedToolSummary;
import com.janyee.agent.runtime.loop.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stateless evaluator that converts a {@link PlanStepRule} plus a step's accumulated tool
 * summaries (and optionally the artifact content produced in this run) into a
 * human-readable list of completion violations. An empty list means the step may legally
 * transition to COMPLETED.
 *
 * Kept small and side-effect-free so both PlanUpdateTool (on every COMPLETED transition)
 * and DefaultToolLoopOrchestrator (on run-end gate) can call it without coupling.
 */
public final class PlanStepRuleEvaluator {

    private PlanStepRuleEvaluator() {
    }

    public static List<String> evaluateCompletion(PlanStep step, PlanStepRule rule) {
        return evaluateCompletion(step, rule, null);
    }

    /**
     * Evaluate whether the step's accumulated work satisfies the skill's declared rule.
     * Returns violations as user-facing messages (empty = OK).
     *
     * When the rule points at another step via {@code reuseStep}, resolution is handled by
     * {@link SkillGuard#stepRule(String)} — the caller passes the effective rule here.
     *
     * @param latestArtifactContent the markdown / text content of the most recent successful
     *     {@code artifact.*} invocation in this run. Required to check
     *     {@link PlanStepRule#reportSection()} heading anchors; may be null if no artifact
     *     has been produced or the check is not relevant.
     */
    public static List<String> evaluateCompletion(PlanStep step, PlanStepRule rule, String latestArtifactContent) {
        List<String> violations = new ArrayList<>();
        if (step == null || rule == null || rule.isEmpty()) {
            return violations;
        }
        List<CompletedToolSummary> summaries = step.toolSummaries();

        if (!rule.requiresSuccess().isEmpty()) {
            boolean matched = rule.requiresSuccess().stream().anyMatch(required ->
                    summaries.stream().anyMatch(s -> required.equals(s.toolName()))
            );
            if (!matched) {
                violations.add("step '" + step.id() + "' requires a successful invocation of one of "
                        + rule.requiresSuccess() + "; none recorded yet");
            }
        }

        if (rule.minQueries() > 0) {
            long queryCount = summaries.stream()
                    .filter(s -> "db.query".equals(s.toolName()))
                    .count();
            if (queryCount < rule.minQueries()) {
                violations.add("step '" + step.id() + "' requires at least " + rule.minQueries()
                        + " db.query result(s); got " + queryCount);
            }
        }

        // requiredTables：skill 声明了必须查到哪些 schema.table 才算完整，evaluator 逐一核对。
        // CompletedToolSummary.argumentsFingerprint() 里存的是规范化后的小写 SQL，含 schema.table 字面量；
        // 我们对每个 required table 在所有 db.query 的指纹中做子串搜索 —— 只要有一次成功 db.query
        // 触到了该表就算满足。这样堵住了 "只查 1 张，其它靠记忆" 的漏洞。
        if (!rule.requiredTables().isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (String requiredTable : rule.requiredTables()) {
                if (requiredTable == null || requiredTable.isBlank()) {
                    continue;
                }
                String needle = requiredTable.toLowerCase(Locale.ROOT).trim();
                boolean touched = summaries.stream()
                        .filter(s -> "db.query".equals(s.toolName()))
                        .anyMatch(s -> {
                            String fp = s.argumentsFingerprint();
                            return fp != null && fp.contains(needle);
                        });
                if (!touched) {
                    missing.add(requiredTable);
                }
            }
            if (!missing.isEmpty()) {
                violations.add("step '" + step.id() + "' still missing successful db.query on required table(s) "
                        + missing + "; do not fabricate values — query each listed table once before COMPLETED.");
            }
        }

        if (!rule.zeroRowsAllowed()) {
            boolean hasNonZero = summaries.stream()
                    .filter(s -> "db.query".equals(s.toolName()))
                    .anyMatch(s -> s.rowCount() > 0);
            if (!hasNonZero) {
                violations.add("step '" + step.id() + "' disallows zero-row-only completion; re-query with a filter that returns data, or flip to SKIPPED with resultNote");
            }
        }

        PlanStepRule.ReportSection reportSection = rule.reportSection();
        if (reportSection != null) {
            // Only enforce heading/placeholder anchors when this step actually produced an
            // artifact — i.e. when requiresSuccess names an artifact.* tool. Non-report
            // steps just carry reportSection as descriptive context for the LLM.
            boolean stepIsReport = rule.requiresSuccess().stream()
                    .anyMatch(tool -> tool != null && tool.startsWith("artifact."));
            if (stepIsReport) {
                if (latestArtifactContent == null || latestArtifactContent.isBlank()) {
                    if (!reportSection.heading().isBlank() || !reportSection.placeholders().isEmpty()) {
                        violations.add("step '" + step.id() + "' must produce an artifact whose content matches the skill's reportSection; no artifact content captured yet");
                    }
                } else {
                    if (!reportSection.heading().isBlank() && !latestArtifactContent.contains(reportSection.heading())) {
                        violations.add("step '" + step.id() + "' artifact is missing required heading '"
                                + reportSection.heading() + "' — follow the skill's markdown template exactly");
                    }
                    // Every declared placeholder label must appear somewhere in the artifact;
                    // this catches the classic "LLM only wrote 3 of 4 bullets" failure.
                    for (PlanStepRule.Placeholder placeholder : reportSection.placeholders()) {
                        String label = placeholder.label();
                        if (label == null || label.isBlank()) {
                            continue;
                        }
                        if (!latestArtifactContent.contains(label)) {
                            violations.add("step '" + step.id() + "' artifact is missing required anchor '"
                                    + label + "' — skill's reportSection requires it");
                        }
                    }
                }
            }
        }

        return violations;
    }

    /**
     * Convenience: resolve the guard's rule for a step, evaluate, and return a single-line
     * reason string (or empty Optional if no violation).
     */
    public static Optional<String> firstViolation(PlanStep step, SkillGuard guard) {
        if (step == null || guard == null) {
            return Optional.empty();
        }
        PlanStepRule rule = guard.stepRule(step.id()).orElse(null);
        if (rule == null) {
            return Optional.empty();
        }
        List<String> violations = evaluateCompletion(step, rule);
        return violations.isEmpty() ? Optional.empty() : Optional.of(violations.get(0));
    }
}
