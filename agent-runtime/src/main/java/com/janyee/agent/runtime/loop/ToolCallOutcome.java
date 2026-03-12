package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.ToolResult;

public record ToolCallOutcome(
        ToolCallRequest request,
        ToolCallDecision decision,
        boolean executed,
        boolean success,
        ToolResult toolResult,
        String errorMessage,
        long durationMillis
) {
}
