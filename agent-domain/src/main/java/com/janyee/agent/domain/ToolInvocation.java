package com.janyee.agent.domain;

public record ToolInvocation(
        String sessionId,
        String toolName,
        String argumentsJson
) {
}
