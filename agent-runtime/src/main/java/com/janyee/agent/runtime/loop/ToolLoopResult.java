package com.janyee.agent.runtime.loop;

import java.util.List;

public record ToolLoopResult(
        boolean success,
        ToolLoopState finalState,
        String finalAssistantText,
        List<ToolCallOutcome> toolOutcomes,
        List<ToolLoopIteration> iterations,
        String errorMessage
) {
}
