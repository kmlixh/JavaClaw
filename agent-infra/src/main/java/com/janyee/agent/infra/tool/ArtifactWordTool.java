package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.tool.AgentTool;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Component
public class ArtifactWordTool implements AgentTool {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ArtifactWordTool(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "artifact.word";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Generate a downloadable Word document (.docx)",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"name\",\"content\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String name = ensureExtension(args.path("name").asText("document.docx"), ".docx");
            byte[] bytes = createDocx(args.path("title").asText(""), args.path("content").asText(""));
            ArtifactRecord artifact = artifactService.saveBinaryArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "word",
                    name,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    bytes
            );
            return artifactResult("Generated Word document " + artifact.name(), artifact);
        } catch (Exception error) {
            return new ToolResult(false, "word generation failed", "{}", "[]", error.getMessage());
        }
    }

    private byte[] createDocx(String title, String content) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!title.isBlank()) {
                XWPFParagraph titleParagraph = document.createParagraph();
                XWPFRun titleRun = titleParagraph.createRun();
                titleRun.setBold(true);
                titleRun.setFontSize(18);
                titleRun.setText(title);
            }
            for (String line : content.split("\\R", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(line);
            }
            document.write(output);
            return output.toByteArray();
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
