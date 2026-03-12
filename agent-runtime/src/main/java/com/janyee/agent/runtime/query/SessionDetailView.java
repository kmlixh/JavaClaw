package com.janyee.agent.runtime.query;

import java.time.Instant;
import java.util.List;

public record SessionDetailView(
        String sessionId,
        String agentId,
        String userId,
        String channel,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<SessionMessageView> messages
) {
}
