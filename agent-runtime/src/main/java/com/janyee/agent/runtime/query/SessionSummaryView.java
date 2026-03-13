package com.janyee.agent.runtime.query;

import java.time.Instant;

public record SessionSummaryView(
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
