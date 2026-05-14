package com.janyee.agent.runtime.lab;

import java.time.Instant;

public record AgentLabIterationView(
        Long id,
        String taskId,
        int iterationNo,
        String status,
        String agentSnapshotJson,
        String skillSnapshotsJson,
        String testResultsJson,
        String runTracesJson,
        Integer passedCount,
        Integer totalCount,
        String evaluationSummary,
        String fixPlanJson,
        String metaLlmError,
        String progressStep,           // 实时进度提示(IN_PROGRESS 时非空,终态清空)
        Instant createdAt
) {
}
