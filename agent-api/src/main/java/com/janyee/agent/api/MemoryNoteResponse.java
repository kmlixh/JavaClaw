package com.janyee.agent.api;

import java.time.Instant;

public record MemoryNoteResponse(
        Long id,
        String agentId,
        String sessionId,
        String runId,
        String source,
        String content,
        Instant createdAt
) {
}
