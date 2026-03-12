package com.janyee.agent.security;

import java.time.Instant;

public record ApprovalRequestView(
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
