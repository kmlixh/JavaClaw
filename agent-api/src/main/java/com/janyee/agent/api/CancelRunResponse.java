package com.janyee.agent.api;

/**
 * Result of POST /api/runs/{runId}/cancel.
 *
 * @param runId       the run that was targeted
 * @param signalled   true = registry had the run and the cancel flag was flipped; the
 *                    orchestrator will exit on its next checkpoint and the DB status
 *                    will flip to {@code CANCELLED}.
 * @param dbUpdated   true = registry did not have the run but the DB still showed a
 *                    non-terminal status, so we forced it to {@code CANCELLED} as a
 *                    fallback (stale/zombie run reconciliation).
 * @param message     human-readable summary of what happened.
 */
public record CancelRunResponse(
        String runId,
        boolean signalled,
        boolean dbUpdated,
        String message
) {
}
