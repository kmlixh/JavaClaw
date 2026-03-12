package com.janyee.agent.runtime.loop;

public interface ApprovalRequirementService {
    boolean requiresApproval(ToolLoopContext context, String toolName);
}
