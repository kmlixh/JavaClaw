package com.janyee.agent.runtime.query;

import java.time.Instant;

public record MemoryNoteView(
        Long id,
        String agentId,
        String sessionId,
        String runId,
        String source,
        String content,
        Instant createdAt
) {
}
