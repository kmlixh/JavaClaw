package com.janyee.agent.runtime.query;

import java.time.Instant;

public record SessionMessageView(
        Long id,
        String runId,
        String role,
        String messageType,
        String content,
        String toolName,
        String toolArgsJson,
        String toolResultJson,
        Long seqNo,
        Instant createdAt
) {
}
