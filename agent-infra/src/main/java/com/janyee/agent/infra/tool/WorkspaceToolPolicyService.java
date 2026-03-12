package com.janyee.agent.infra.tool;

import com.janyee.agent.tool.policy.ToolPolicyService;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class WorkspaceToolPolicyService implements ToolPolicyService {

    private final WorkspaceService workspaceService;

    public WorkspaceToolPolicyService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public boolean isAllowed(String agentId, String toolName) {
        return workspaceService.getToolPolicy(agentId).isAllowed(toolName);
    }
}
