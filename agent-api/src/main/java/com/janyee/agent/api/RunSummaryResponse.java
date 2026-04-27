package com.janyee.agent.api;

import java.time.Instant;

public record RunSummaryResponse(
        String runId,
        String sessionId,
        String status,
        String detail,
        Instant createdAt,
        Instant updatedAt
) {
}
