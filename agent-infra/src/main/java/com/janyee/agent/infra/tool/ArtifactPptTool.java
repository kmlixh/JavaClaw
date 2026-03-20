package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.tool.AgentTool;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.util.Map;

@Component
public class ArtifactPptTool implements AgentTool {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ArtifactPptTool(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "artifact.ppt";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Generate a downloadable PowerPoint presentation (.pptx)",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"name\",\"content\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String name = ensureExtension(args.path("name").asText("slides.pptx"), ".pptx");
            byte[] bytes = createPresentation(args.path("title").asText(""), args.path("content").asText(""));
            ArtifactRecord artifact = artifactService.saveBinaryArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "ppt",
                    name,
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    bytes
            );
            return artifactResult("Generated PowerPoint file " + artifact.name(), artifact);
        } catch (Exception error) {
            return new ToolResult(false, "ppt generation failed", "{}", "[]", error.getMessage());
        }
    }

    private byte[] createPresentation(String title, String content) throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox titleBox = slide.createTextBox();
            titleBox.setAnchor(new Rectangle(40, 30, 620, 50));
            titleBox.addNewTextParagraph().addNewTextRun().setText(title == null || title.isBlank() ? "Presentation" : title);

            XSLFTextBox bodyBox = slide.createTextBox();
            bodyBox.setAnchor(new Rectangle(40, 110, 620, 320));
            for (String line : content.split("\\R")) {
                XSLFTextParagraph paragraph = bodyBox.addNewTextParagraph();
                XSLFTextRun run = paragraph.addNewTextRun();
                run.setText(line);
            }
            slideShow.write(output);
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
