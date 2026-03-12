package com.janyee.agent.runtime.loop;

import com.janyee.agent.tool.policy.ToolPolicyService;
import org.springframework.stereotype.Component;

@Component
public class DefaultToolLoopPolicy implements ToolLoopPolicy {

    private final ApprovalRequirementService approvalRequirementService;
    private final ToolPolicyService toolPolicyService;

    public DefaultToolLoopPolicy(
            ApprovalRequirementService approvalRequirementService,
            ToolPolicyService toolPolicyService
    ) {
        this.approvalRequirementService = approvalRequirementService;
        this.toolPolicyService = toolPolicyService;
    }

    @Override
    public ToolCallDecision evaluate(ToolLoopContext context, ToolCallRequest request) {
        if (!toolPolicyService.isAllowed(context.agentId(), request.toolName())) {
            return new ToolCallDecision(false, false, "tool not allowed by workspace policy", request.toolName(), request.argumentsJson());
        }
        if (approvalRequirementService.requiresApproval(context, request.toolName())) {
            return new ToolCallDecision(true, true, "approval required", request.toolName(), request.argumentsJson());
        }
        return new ToolCallDecision(true, false, "allowed", request.toolName(), request.argumentsJson());
    }
}
