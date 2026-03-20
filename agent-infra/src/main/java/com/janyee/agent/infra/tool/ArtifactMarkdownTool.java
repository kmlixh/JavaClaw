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
public class ArtifactMarkdownTool implements AgentTool {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ArtifactMarkdownTool(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "artifact.markdown";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Generate a markdown file artifact that can be downloaded later",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"name\",\"content\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String name = ensureExtension(args.path("name").asText("document.md"), ".md");
            ArtifactRecord artifact = artifactService.saveTextArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "markdown",
                    name,
                    "text/markdown; charset=UTF-8",
                    args.path("content").asText("")
            );
            return artifactResult("Generated markdown file " + artifact.name(), artifact);
        } catch (Exception error) {
            return new ToolResult(false, "markdown generation failed", "{}", "[]", error.getMessage());
        }
    }

    private ToolResult artifactResult(String summary, ArtifactRecord artifact) throws Exception {
        return new ToolResult(
                true,
                summary,
                objectMapper.writeValueAsString(Map.of(
                        "displayType", "artifact",
                        "artifactId", artifact.id(),
                        "name", artifact.name(),
                        "contentType", artifact.contentType()
                )),
                objectMapper.writeValueAsString(java.util.List.of(Map.of(
                        "artifactId", artifact.id(),
                        "name", artifact.name(),
                        "path", artifact.path(),
                        "contentType", artifact.contentType()
                ))),
                null
        );
    }

    private String ensureExtension(String value, String extension) {
        return value.toLowerCase().endsWith(extension) ? value : value + extension;
    }
}
