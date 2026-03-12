package com.janyee.agent.domain;

public record ToolInvocation(
        String agentId,
        String runId,
        String sessionId,
        String userId,
        String toolName,
        String argumentsJson
) {
}
