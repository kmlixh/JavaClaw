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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ArtifactMarkdownTool implements AgentTool {

    private static final Pattern DATA_URL_IMAGE_PATTERN = Pattern.compile(
            "!\\[([^\\]]*)]\\((data:(image/[^;]+);base64,([^\\)\\s]+))\\)"
    );

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
                "Generate a markdown document artifact with inline preview support. If the document needs images, pass image assets or embed data URL images; the tool stores them on the server and rewrites markdown image links.",
                """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Markdown file name, .md will be appended when missing"},
                  "content":{"type":"string","description":"Markdown document body"},
                  "images":{"type":"array","description":"Optional image resources to save and reference from markdown","items":{"type":"object","properties":{
                    "name":{"type":"string"},
                    "contentType":{"type":"string","description":"For example image/png or image/svg+xml"},
                    "placeholder":{"type":"string","description":"Text or URL placeholder in markdown to replace with the saved image URL"},
                    "sourcePath":{"type":"string","description":"Server-local readable image path to copy into artifacts"},
                    "dataUrl":{"type":"string","description":"data:image/...;base64,..."},
                    "base64":{"type":"string","description":"Raw base64 image bytes"},
                    "svg":{"type":"string","description":"SVG XML content"},
                    "content":{"type":"string","description":"Text image content, mainly for SVG"}
                  }}}
                },"required":["name","content"]}
                """
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String name = ensureExtension(args.path("name").asText("document.md"), ".md");
            MarkdownAssets markdownAssets = materializeImages(invocation, args.path("content").asText(""), args.path("images"));
            String content = markdownAssets.markdown();
            ArtifactRecord artifact = artifactService.saveTextArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "markdown",
                    name,
                    "text/markdown; charset=UTF-8",
                    content
            );
            return artifactResult("Generated markdown document " + artifact.name(), artifact, content, markdownAssets.assets());
        } catch (Exception error) {
            return new ToolResult(false, "markdown generation failed", "{}", "[]", error.getMessage());
        }
    }

    private MarkdownAssets materializeImages(ToolInvocation invocation, String markdown, JsonNode imagesNode) throws Exception {
        String rewritten = markdown == null ? "" : markdown;
        List<Map<String, Object>> assets = new ArrayList<>();

        if (imagesNode != null && imagesNode.isArray()) {
            for (JsonNode imageNode : imagesNode) {
                ImageAsset image = parseImageAsset(imageNode);
                if (image == null) {
                    continue;
                }
                ArtifactRecord imageArtifact = saveImageArtifact(invocation, image);
                String url = artifactUrl(imageArtifact);
                assets.add(assetMap(imageArtifact, url));
                String placeholder = image.placeholder();
                if (placeholder != null && !placeholder.isBlank()) {
                    rewritten = rewritten.replace(placeholder, url);
                } else if (!rewritten.contains(url)) {
                    rewritten += "\n\n![](" + url + ")\n";
                }
            }
        }

        Matcher matcher = DATA_URL_IMAGE_PATTERN.matcher(rewritten);
        StringBuffer buffer = new StringBuffer();
        int inlineIndex = 1;
        while (matcher.find()) {
            String alt = matcher.group(1);
            String dataUrl = matcher.group(2);
            String contentType = matcher.group(3);
            String base64 = matcher.group(4);
            ImageAsset image = new ImageAsset(
                    "markdown-image-" + inlineIndex + extensionForContentType(contentType),
                    contentType,
                    null,
                    base64
            );
            ArtifactRecord imageArtifact = saveImageArtifact(invocation, image);
            String url = artifactUrl(imageArtifact);
            assets.add(assetMap(imageArtifact, url));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![" + alt + "](" + url + ")"));
            inlineIndex++;
        }
        matcher.appendTail(buffer);

        return new MarkdownAssets(buffer.toString(), assets);
    }

    private ImageAsset parseImageAsset(JsonNode imageNode) throws IOException {
        if (imageNode == null || !imageNode.isObject()) {
            return null;
        }
        String contentType = defaultIfBlank(imageNode.path("contentType").asText(""), "image/png");
        String name = defaultIfBlank(imageNode.path("name").asText(""), "markdown-image" + extensionForContentType(contentType));
        String placeholder = imageNode.path("placeholder").asText("");
        String sourcePath = imageNode.path("sourcePath").asText("");
        if (!sourcePath.isBlank()) {
            Path path = Path.of(sourcePath);
            byte[] bytes = Files.readAllBytes(path);
            String probedContentType = Files.probeContentType(path);
            String resolvedContentType = defaultIfBlank(probedContentType, contentType);
            String resolvedName = defaultIfBlank(imageNode.path("name").asText(""), path.getFileName().toString());
            return new ImageAsset(resolvedName, resolvedContentType, placeholder, Base64.getEncoder().encodeToString(bytes));
        }
        String dataUrl = imageNode.path("dataUrl").asText("");
        if (!dataUrl.isBlank()) {
            int comma = dataUrl.indexOf(',');
            if (comma > 0) {
                String header = dataUrl.substring(0, comma);
                String parsedType = parseDataUrlContentType(header);
                return new ImageAsset(name, defaultIfBlank(parsedType, contentType), placeholder, dataUrl.substring(comma + 1));
            }
        }
        String base64 = imageNode.path("base64").asText("");
        if (!base64.isBlank()) {
            return new ImageAsset(name, contentType, placeholder, base64);
        }
        String svg = imageNode.path("svg").asText("");
        if (!svg.isBlank()) {
            return new ImageAsset(ensureExtension(name, ".svg"), "image/svg+xml", placeholder,
                    Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8)));
        }
        String content = imageNode.path("content").asText("");
        if (!content.isBlank() && "image/svg+xml".equalsIgnoreCase(contentType)) {
            return new ImageAsset(ensureExtension(name, ".svg"), contentType,
                    placeholder, Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        }
        return null;
    }

    private ArtifactRecord saveImageArtifact(ToolInvocation invocation, ImageAsset image) {
        return artifactService.saveBinaryArtifact(
                invocation.agentId(),
                invocation.sessionId(),
                invocation.runId(),
                "markdown-image",
                image.name(),
                image.contentType(),
                Base64.getDecoder().decode(image.base64())
        );
    }

    private ToolResult artifactResult(String summary, ArtifactRecord artifact, String markdown, List<Map<String, Object>> assets) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "markdown");
        data.put("artifactId", artifact.id());
        data.put("name", artifact.name());
        data.put("contentType", artifact.contentType());
        data.put("markdown", markdown);
        data.put("assets", assets);

        List<Map<String, Object>> artifactList = new ArrayList<>();
        artifactList.add(Map.of(
                "artifactId", artifact.id(),
                "name", artifact.name(),
                "path", artifact.path(),
                "contentType", artifact.contentType()
        ));
        artifactList.addAll(assets);

        return new ToolResult(
                true,
                summary,
                objectMapper.writeValueAsString(data),
                objectMapper.writeValueAsString(artifactList),
                null
        );
    }

    private String ensureExtension(String value, String extension) {
        return value.toLowerCase().endsWith(extension) ? value : value + extension;
    }

    private Map<String, Object> assetMap(ArtifactRecord artifact, String url) {
        return Map.of(
                "artifactId", artifact.id(),
                "name", artifact.name(),
                "path", artifact.path(),
                "contentType", artifact.contentType(),
                "url", url
        );
    }

    private String artifactUrl(ArtifactRecord artifact) {
        return "/api/artifacts/" + artifact.id() + "/download";
    }

    private String extensionForContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.contains("svg")) {
            return ".svg";
        }
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return ".jpg";
        }
        if (normalized.contains("gif")) {
            return ".gif";
        }
        if (normalized.contains("webp")) {
            return ".webp";
        }
        return ".png";
    }

    private String parseDataUrlContentType(String header) {
        if (header == null || !header.startsWith("data:")) {
            return "";
        }
        int semicolon = header.indexOf(';');
        if (semicolon <= "data:".length()) {
            return "";
        }
        return header.substring("data:".length(), semicolon);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ImageAsset(String name, String contentType, String placeholder, String base64) {}

    private record MarkdownAssets(String markdown, List<Map<String, Object>> assets) {}
}
