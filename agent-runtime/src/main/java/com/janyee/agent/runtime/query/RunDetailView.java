package com.janyee.agent.runtime.query;

import java.time.Instant;
import java.util.List;

public record RunDetailView(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String llmConfigId,
        String llmProvider,
        String llmModel,
        String status,
        String detail,
        String requestMessage,
        String requestReferencesJson,
        String requestAttachmentsJson,
        // Snapshot of the run's plan. Nullable — only runs with a plan carry this.
        String planJson,
        Instant createdAt,
        Instant updatedAt,
        List<ToolAuditLogView> toolAudits,
        List<ArtifactView> artifacts
) {
}
