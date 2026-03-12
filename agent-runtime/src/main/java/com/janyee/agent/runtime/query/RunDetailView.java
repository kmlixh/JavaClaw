package com.janyee.agent.runtime.query;

import java.time.Instant;
import java.util.List;

public record RunDetailView(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String status,
        String detail,
        Instant createdAt,
        Instant updatedAt,
        List<ToolAuditLogView> toolAudits
) {
}
