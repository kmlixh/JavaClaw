package com.janyee.agent.runtime.admin;

import java.time.Instant;

public record MemoryNoteView(
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
