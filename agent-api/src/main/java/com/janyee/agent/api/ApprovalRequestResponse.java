package com.janyee.agent.api;

import java.time.Instant;

public record ApprovalRequestResponse(
        String approvalRequestId,
        String runId,
        String sessionId,
        String agentId,
        String toolName,
        String argumentsJson,
        String reason,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
