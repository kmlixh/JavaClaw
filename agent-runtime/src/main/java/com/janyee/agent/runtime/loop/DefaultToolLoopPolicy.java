package com.janyee.agent.runtime.loop;

import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.tool.policy.ToolPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DefaultToolLoopPolicy implements ToolLoopPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolLoopPolicy.class);

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
        String toolName = request.toolName();
        if (!toolPolicyService.isAllowed(context.agentId(), toolName)) {
            return new ToolCallDecision(false, false, "tool not allowed by workspace policy", toolName, request.argumentsJson());
        }

        // Skill-driven artifact gate: when the active skill declares a specific artifact.*
        // deliverable via requiresSuccess, reject any other artifact.* call so the LLM can't
        // silently substitute a .word/.pptx when .md is required.
        SkillGuard guard = context.guard();
        if (guard != null && toolName != null && toolName.startsWith("artifact.")) {
            Set<String> allowed = guard.allowedArtifactTools();
            if (!allowed.isEmpty() && !allowed.contains(toolName)) {
                String reason = "skill restricts artifact output to " + allowed + "; refusing " + toolName;
                log.warn("tool.loop.artifact_not_allowed runId={}, toolName={}, allowed={}",
                        context.runId(), toolName, allowed);
                return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
            }
            // Also refuse artifact.* when the skill requires a plan but none has been
            // created — prevents LLM jumping straight to the deliverable without executing
            // the data-gathering steps.
            if (guard.hasPlanEnforcement() && context.runPlan().isEmpty()) {
                String reason = "skill requires a plan with steps " + guard.requiredPlanStepIds()
                        + " before producing any artifact.*; call plan.create first and execute the data steps.";
                log.warn("tool.loop.artifact_before_plan runId={}, toolName={}", context.runId(), toolName);
                return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
            }
        }

        if (approvalRequirementService.requiresApproval(context, toolName)) {
            return new ToolCallDecision(true, true, "approval required", toolName, request.argumentsJson());
        }
        return new ToolCallDecision(true, false, "allowed", toolName, request.argumentsJson());
    }
}
