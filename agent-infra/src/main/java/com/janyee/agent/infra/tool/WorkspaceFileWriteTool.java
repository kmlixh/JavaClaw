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
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

@Component
public class WorkspaceFileWriteTool implements AgentTool {

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public WorkspaceFileWriteTool(WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "file.write";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Write a UTF-8 text file inside the current agent workspace",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"append\":{\"type\":\"boolean\"}},\"required\":[\"path\",\"content\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            Path path = WorkspacePathSupport.resolvePath(workspaceService, invocation.agentId(), args.path("path").asText());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            boolean append = args.path("append").asBoolean(false);
            OpenOption[] options = append
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            Files.writeString(path, args.path("content").asText(""), StandardCharsets.UTF_8, options);
            return new ToolResult(
                    true,
                    "Wrote file " + path.getFileName(),
                    objectMapper.writeValueAsString(Map.of(
                            "path", workspaceService.getWorkspaceRoot(invocation.agentId()).relativize(path).toString().replace('\\', '/'),
                            "append", append
                    )),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "file write failed", "{}", "[]", error.getMessage());
        }
    }
}
