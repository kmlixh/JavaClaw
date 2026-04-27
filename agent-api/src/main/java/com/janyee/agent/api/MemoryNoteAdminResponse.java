package com.janyee.agent.api;

import java.time.Instant;

public record MemoryNoteAdminResponse(
        Long id,
        String agentId,
        String sessionId,
        String runId,
        String scope,
        String source,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
