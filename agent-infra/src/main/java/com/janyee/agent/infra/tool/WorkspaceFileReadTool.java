package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class WorkspaceFileReadTool implements AgentTool {

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public WorkspaceFileReadTool(WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "file.read";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Read a UTF-8 text file inside the current agent workspace",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            Path path = WorkspacePathSupport.resolvePath(workspaceService, invocation.agentId(), args.path("path").asText());
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return new ToolResult(false, "file not found", "{}", "[]", "file not found: " + path.getFileName());
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String dataJson = objectMapper.writeValueAsString(Map.of(
                    "path", workspaceService.getWorkspaceRoot(invocation.agentId()).relativize(path).toString().replace('\\', '/'),
                    "content", content
            ));
            return new ToolResult(true, "Read file " + path.getFileName(), dataJson, "[]", null);
        } catch (Exception error) {
            return new ToolResult(false, "file read failed", "{}", "[]", error.getMessage());
        }
    }
}
