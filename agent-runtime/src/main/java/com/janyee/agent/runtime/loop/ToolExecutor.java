package com.janyee.agent.runtime.loop;

public interface ToolExecutor {
    ToolCallOutcome execute(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision);
}
