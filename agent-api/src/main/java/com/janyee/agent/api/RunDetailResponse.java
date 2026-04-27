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
        String requestMessage,
        String requestReferencesJson,
        String requestAttachmentsJson,
        // Snapshot of the run's plan (RunPlan.toSnapshot() serialized). Nullable — not every
        // run has a plan. Historical runs and in-flight runs both surface this so the UI can
        // show "how this run organized its steps" retroactively.
        String planJson,
        Instant createdAt,
        Instant updatedAt,
        List<ToolAuditLogResponse> toolAudits,
        List<ArtifactResponse> artifacts
) {
}
