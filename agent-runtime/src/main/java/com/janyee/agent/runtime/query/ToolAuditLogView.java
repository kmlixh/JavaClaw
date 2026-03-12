package com.janyee.agent.runtime.query;

import java.time.Instant;

public record ToolAuditLogView(
        Long id,
        String requestId,
        String toolName,
        String phase,
        boolean allowed,
        boolean approvalRequired,
        Boolean success,
        Boolean executed,
        String reason,
        String argumentsJson,
        String resultSummary,
        String errorMessage,
        Long durationMillis,
        Instant createdAt
) {
}
