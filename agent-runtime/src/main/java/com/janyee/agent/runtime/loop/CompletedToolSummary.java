package com.janyee.agent.runtime.loop;

import java.time.Instant;

/**
 * Compact trace of a successful tool execution that the LLM can consult on later iterations,
 * so it does not re-issue the same db.query / schema inspection after context pressure evicts it
 * from the "last tool raw output" window.
 *
 * <p>{@code firstRowJson} captures the first parsed row of the tool's data output (truncated to
 * a reasonable size). Used by {@link com.janyee.agent.runtime.skill.PlanStepRuleEvaluator} to
 * verify acceptance criteria like "this column must appear" / "at least one numeric value must
 * be non-zero" — without those data values, the evaluator can only check tool-name and rowCount,
 * which doesn't catch "queries returned (cnt_2g=0, cnt_4g=0, cnt_5g=0)" — a legitimate concern
 * that should make the LLM re-query.</p>
 */
public record CompletedToolSummary(
        String toolName,
        String argumentsFingerprint,
        String summary,
        int rowCount,
        Instant completedAt,
        String stepId,
        String firstRowJson
) {
    private static final int MAX_FIRST_ROW_LEN = 500;

    public static CompletedToolSummary of(String toolName,
                                          String argumentsFingerprint,
                                          String summary,
                                          int rowCount,
                                          String stepId) {
        return of(toolName, argumentsFingerprint, summary, rowCount, stepId, null);
    }

    public static CompletedToolSummary of(String toolName,
                                          String argumentsFingerprint,
                                          String summary,
                                          int rowCount,
                                          String stepId,
                                          String firstRowJson) {
        String row = firstRowJson;
        if (row != null && row.length() > MAX_FIRST_ROW_LEN) {
            row = row.substring(0, MAX_FIRST_ROW_LEN) + "…";
        }
        return new CompletedToolSummary(
                toolName,
                argumentsFingerprint,
                summary == null ? "" : summary,
                Math.max(rowCount, 0),
                Instant.now(),
                stepId,
                row == null ? "" : row
        );
    }
}
