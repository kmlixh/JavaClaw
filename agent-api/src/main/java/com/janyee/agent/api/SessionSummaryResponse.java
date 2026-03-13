package com.janyee.agent.api;

import java.time.Instant;

public record SessionSummaryResponse(
        String sessionId,
        String title,
        String agentId,
        String userId,
        String channel,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
