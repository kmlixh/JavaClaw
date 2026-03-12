package com.janyee.agent.runtime.loop;

public interface ToolAuditService {
    void recordPolicyDecision(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision);

    void recordExecutionOutcome(ToolLoopContext context, ToolCallOutcome outcome);
}
