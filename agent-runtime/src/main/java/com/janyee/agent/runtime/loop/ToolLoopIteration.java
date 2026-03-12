package com.janyee.agent.runtime.loop;

public record ToolLoopIteration(
        int iterationNo,
        String modelRequestSummary,
        ToolCallRequest toolCallRequest,
        ToolCallDecision decision,
        ToolCallOutcome outcome,
        ToolLoopState startState,
        ToolLoopState endState
) {
}
