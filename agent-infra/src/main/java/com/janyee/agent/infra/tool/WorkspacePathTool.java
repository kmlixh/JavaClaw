package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Component
public class WorkspacePathTool implements AgentTool {

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public WorkspacePathTool(WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "workspace.path";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Return the absolute path of the current agent workspace root. Use this instead of shell commands when the user asks for the current workspace directory.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            Path workspaceRoot = workspaceService.getWorkspaceRoot(invocation.agentId()).toAbsolutePath().normalize();
            String dataJson = objectMapper.writeValueAsString(Map.of(
                    "agentId", invocation.agentId(),
                    "workspaceRoot", workspaceRoot.toString()
            ));
            return new ToolResult(true, "Workspace root path resolved", dataJson, "[]", null);
        } catch (Exception error) {
            return new ToolResult(false, "workspace path failed", "{}", "[]", error.getMessage());
        }
    }
}
