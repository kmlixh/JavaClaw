package com.janyee.agent.runtime.loop;

import java.time.Instant;

/**
 * Compact trace of a successful tool execution that the LLM can consult on later iterations,
 * so it does not re-issue the same db.query / schema inspection after context pressure evicts it
 * from the "last tool raw output" window.
 */
public record CompletedToolSummary(
        String toolName,
        String argumentsFingerprint,
        String summary,
        int rowCount,
        Instant completedAt,
        String stepId
) {
    public static CompletedToolSummary of(String toolName,
                                          String argumentsFingerprint,
                                          String summary,
                                          int rowCount,
                                          String stepId) {
        return new CompletedToolSummary(
                toolName,
                argumentsFingerprint,
                summary == null ? "" : summary,
                Math.max(rowCount, 0),
                Instant.now(),
                stepId
        );
    }
}
