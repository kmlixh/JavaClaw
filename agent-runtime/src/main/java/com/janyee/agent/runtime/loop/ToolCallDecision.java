package com.janyee.agent.runtime.loop;

public record ToolCallDecision(
        boolean allowed,
        boolean approvalRequired,
        String reason,
        String normalizedToolName,
        String normalizedArgumentsJson
) {
}
