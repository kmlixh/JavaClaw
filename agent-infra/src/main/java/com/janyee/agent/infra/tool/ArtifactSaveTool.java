package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ArtifactSaveTool implements AgentTool {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ArtifactSaveTool(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "artifact.save";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Persist a text artifact under the current run and workspace",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"artifactType\":{\"type\":\"string\"},\"contentType\":{\"type\":\"string\"}},\"required\":[\"name\",\"content\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            ArtifactRecord artifact = artifactService.saveTextArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    args.path("artifactType").asText("text"),
                    args.path("name").asText("artifact.txt"),
                    args.path("contentType").asText("text/plain; charset=UTF-8"),
                    args.path("content").asText("")
            );
            return new ToolResult(
                    true,
                    "Saved artifact " + artifact.name(),
                    objectMapper.writeValueAsString(Map.of(
                            "artifactId", artifact.id(),
                            "path", artifact.path(),
                            "name", artifact.name()
                    )),
                    objectMapper.writeValueAsString(java.util.List.of(Map.of(
                            "artifactId", artifact.id(),
                            "path", artifact.path(),
                            "name", artifact.name(),
                            "contentType", artifact.contentType()
                    ))),
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "artifact save failed", "{}", "[]", error.getMessage());
        }
    }
}
