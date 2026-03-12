package com.janyee.agent.runtime.worker;

public record WorkerTaskRequest(
        String agentId,
        String sessionId,
        String runId,
        String taskType,
        String payload
) {
}
