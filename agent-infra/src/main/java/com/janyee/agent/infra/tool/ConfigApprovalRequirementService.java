package com.janyee.agent.infra.tool;

import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.runtime.loop.ApprovalRequirementService;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConfigApprovalRequirementService implements ApprovalRequirementService {

    private final AgentPlatformProperties properties;
    private final ApprovalRequestRepository approvalRequestRepository;

    public ConfigApprovalRequirementService(
            AgentPlatformProperties properties,
            ApprovalRequestRepository approvalRequestRepository
    ) {
        this.properties = properties;
        this.approvalRequestRepository = approvalRequestRepository;
    }

    @Override
    public boolean requiresApproval(ToolLoopContext context, String toolName) {
        List<String> requiredTools = properties.approval() != null && properties.approval().requiredTools() != null
                ? properties.approval().requiredTools()
                : List.of();
        if (!requiredTools.contains(toolName)) {
            return false;
        }
        return !approvalRequestRepository.existsByRunIdAndToolNameAndStatus(context.runId(), toolName, "APPROVED");
    }
}
