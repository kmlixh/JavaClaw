package com.janyee.agent.api;

import java.time.Instant;
import java.util.List;

public record SessionDetailResponse(
        String sessionId,
        String title,
        String agentId,
        String userId,
        String channel,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<SessionMessageResponse> messages
) {
}
