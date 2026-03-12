package com.janyee.agent.api;

import java.time.Instant;

public record SessionMessageResponse(
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
