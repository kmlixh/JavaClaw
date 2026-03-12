package com.janyee.agent.runtime.loop;

public interface ToolResultAppender {
    void append(ToolLoopContext context, ToolCallOutcome outcome);
}
