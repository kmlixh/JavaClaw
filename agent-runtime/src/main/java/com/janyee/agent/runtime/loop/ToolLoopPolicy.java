package com.janyee.agent.runtime.loop;

public interface ToolLoopPolicy {
    ToolCallDecision evaluate(ToolLoopContext context, ToolCallRequest request);
}
