package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class WorkspaceFileListTool implements AgentTool {

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public WorkspaceFileListTool(WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "file.list";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "List files and directories inside the current agent workspace",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            Path path = WorkspacePathSupport.resolvePath(workspaceService, invocation.agentId(), args.path("path").asText("."));
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return new ToolResult(false, "directory not found", "{}", "[]", "directory not found");
            }
            try (Stream<Path> stream = Files.list(path)) {
                List<Map<String, Object>> entries = stream
                        .sorted(Comparator.naturalOrder())
                        .limit(200)
                        .map(entry -> Map.<String, Object>of(
                                "path", workspaceService.getWorkspaceRoot(invocation.agentId()).relativize(entry).toString().replace('\\', '/'),
                                "directory", Files.isDirectory(entry)
                        ))
                        .toList();
                return new ToolResult(
                        true,
                        "Listed " + entries.size() + " entries",
                        objectMapper.writeValueAsString(Map.of("entries", entries)),
                        "[]",
                        null
                );
            }
        } catch (Exception error) {
            return new ToolResult(false, "file list failed", "{}", "[]", error.getMessage());
        }
    }
}
