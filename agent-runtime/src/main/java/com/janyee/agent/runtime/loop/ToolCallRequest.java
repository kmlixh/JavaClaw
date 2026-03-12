package com.janyee.agent.runtime.loop;

public record ToolCallRequest(
        String requestId,
        String toolName,
        String argumentsJson,
        String rawModelFragment
) {
}
