package com.janyee.agent.api;

import java.time.Instant;
import java.util.List;

public record RunDetailResponse(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String llmConfigId,
        String llmProvider,
        String llmModel,
        String status,
        String detail,
        Instant createdAt,
        Instant updatedAt,
        List<ToolAuditLogResponse> toolAudits,
        List<ArtifactResponse> artifacts
) {
}
