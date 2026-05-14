package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.artifact.ArtifactBinary;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.tool.AgentTool;
import com.janyee.agent.workspace.WorkspaceService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * doc.normalize —— 把工作区里的大文档(.pdf / .docx)转成结构化 JSON:
 * <ul>
 *   <li>提取每页 / 每段文本 + 稳定段落 ID(基于 hash,跨多次运行一致)</li>
 *   <li>剥除图片 / 嵌入对象(只记录页号 + 占位 id,大幅减小体积)</li>
 *   <li>提取标题层级 → TOC 目录树</li>
 *   <li>统计页数 / 字数</li>
 * </ul>
 *
 * <p>典型用法:Agent 处理几百 MB 的工程方案文档前,先调一次 doc.normalize 把原始文件转成
 * ~5-15% 体积的 JSON,后续审核 / 摘要 / 引用都基于这份 JSON,既省 token 又能精确定位到
 * "第 N 页第 M 段"。</p>
 *
 * <p>同步实现 + 大小上限(默认 100MB)。摘要由 Agent 自己据 normalized.json 生成,
 * 不在工具内调 LLM —— 保持工具职责单一。</p>
 */
@Component
public class DocNormalizeTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(DocNormalizeTool.class);
    /** 最大输入 byte,超过拒绝执行(避免一个 500MB PDF 把进程内存撑爆)。 */
    private static final long MAX_INPUT_BYTES = 100L * 1024 * 1024;

    private final WorkspaceService workspaceService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public DocNormalizeTool(WorkspaceService workspaceService,
                            ArtifactService artifactService,
                            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "doc.normalize";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "把工作区里大文档(.pdf / .docx)解析成结构化 JSON,剥图、提 TOC、给段落稳定 id,"
                        + "用于后续审核 / 引用 / 摘要。输入:path(工作区相对路径)或 artifactId(已落 artifact 表的 id)。"
                        + "输出:落到 artifacts/<name>.normalized.json,返回新 artifactId + 概要统计。",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\",\"description\":\"工作区相对路径,跟 file.read 同口径\"},"
                        + "\"artifactId\":{\"type\":\"number\",\"description\":\"已有 artifact 的 id,跟 path 二选一\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"输出 JSON 文件名,留空自动据源文件名生成\"}"
                        + "},\"required\":[]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            byte[] sourceBytes;
            String sourceName;
            if (args.hasNonNull("artifactId")) {
                long id = args.get("artifactId").asLong();
                ArtifactBinary loaded = artifactService.loadArtifact(id);
                if (loaded == null) {
                    return new ToolResult(false, "artifact not found",
                            "{}", "[]", "artifactId=" + id + " not found");
                }
                sourceBytes = loaded.content();
                sourceName = loaded.artifact().name();
            } else {
                String rawPath = args.path("path").asText("");
                if (rawPath.isBlank()) {
                    return new ToolResult(false, "missing path or artifactId",
                            "{}", "[]", "either 'path' or 'artifactId' is required");
                }
                Path resolved = WorkspacePathSupport.resolvePath(workspaceService, invocation.agentId(), rawPath);
                if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
                    return new ToolResult(false, "file not found",
                            "{}", "[]", "no file at " + rawPath);
                }
                long size = Files.size(resolved);
                if (size > MAX_INPUT_BYTES) {
                    return new ToolResult(false, "file too large",
                            "{}", "[]",
                            "file size " + size + " bytes exceeds " + MAX_INPUT_BYTES + " limit");
                }
                sourceBytes = Files.readAllBytes(resolved);
                sourceName = resolved.getFileName().toString();
            }

            if (sourceBytes.length > MAX_INPUT_BYTES) {
                return new ToolResult(false, "file too large",
                        "{}", "[]",
                        "file size " + sourceBytes.length + " bytes exceeds " + MAX_INPUT_BYTES + " limit");
            }

            String lowerName = sourceName.toLowerCase(Locale.ROOT);
            ObjectNode normalized;
            if (lowerName.endsWith(".pdf")) {
                normalized = parsePdf(sourceBytes, sourceName);
            } else if (lowerName.endsWith(".docx")) {
                normalized = parseDocx(sourceBytes, sourceName);
            } else {
                return new ToolResult(false, "unsupported format",
                        "{}", "[]", "only .pdf and .docx supported, got " + sourceName);
            }

            String outputName = args.path("name").asText("").isBlank()
                    ? stripExtension(sourceName) + ".normalized.json"
                    : args.path("name").asText();
            String json = objectMapper.writeValueAsString(normalized);
            ArtifactRecord saved = artifactService.saveTextArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "doc-normalized",
                    outputName,
                    "application/json",
                    json
            );

            int totalPages = normalized.path("totalPages").asInt(0);
            int wordCount = normalized.path("wordCount").asInt(0);
            int tocSize = normalized.path("toc").isArray() ? normalized.path("toc").size() : 0;
            int strippedImages = normalized.path("strippedImageCount").asInt(0);
            String summary = String.format(Locale.ROOT,
                    "Normalized %s → %s (pages=%d, words=%d, toc_entries=%d, stripped_images=%d, output_size_kb=%d)",
                    sourceName, saved.name(), totalPages, wordCount, tocSize, strippedImages,
                    json.length() / 1024);

            ObjectNode displayData = objectMapper.createObjectNode();
            displayData.put("displayType", "artifact");
            displayData.put("artifactId", saved.id());
            displayData.put("name", saved.name());
            displayData.put("contentType", saved.contentType());
            displayData.put("totalPages", totalPages);
            displayData.put("wordCount", wordCount);
            displayData.put("tocSize", tocSize);
            displayData.set("toc", normalized.path("toc"));   // 直接把 toc 也回带,让 Agent 不用再开一次

            ArrayNode artifactRefs = objectMapper.createArrayNode();
            ObjectNode artifactRef = objectMapper.createObjectNode();
            artifactRef.put("artifactId", saved.id());
            artifactRef.put("name", saved.name());
            artifactRef.put("path", saved.path());
            artifactRef.put("contentType", saved.contentType());
            artifactRefs.add(artifactRef);

            return new ToolResult(
                    true, summary,
                    objectMapper.writeValueAsString(displayData),
                    objectMapper.writeValueAsString(artifactRefs),
                    null
            );
        } catch (Exception error) {
            log.warn("doc.normalize.failed runId={}, cause={}", invocation.runId(), error.getMessage(), error);
            return new ToolResult(false, "doc.normalize failed",
                    "{}", "[]", error.getMessage());
        }
    }

    // ---------- .docx ----------

    private ObjectNode parseDocx(byte[] bytes, String fileName) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sourceFile", fileName);
        root.put("format", "docx");
        ArrayNode toc = objectMapper.createArrayNode();
        ArrayNode pages = objectMapper.createArrayNode();
        // .docx 没有真正的"页"概念(只有 pageBreak 字符),把整篇当一页,paragraphRange 用全局 seq。
        ObjectNode singlePage = objectMapper.createObjectNode();
        singlePage.put("pageNo", 1);
        ArrayNode paragraphs = objectMapper.createArrayNode();
        int wordCount = 0;
        int imageCount = 0;
        int paraSeq = 0;

        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(input)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text == null) text = "";
                String style = p.getStyle();
                int headingLevel = headingLevelFromStyle(style);
                paraSeq++;
                String id = buildParagraphId(1, paraSeq, text);
                ObjectNode pNode = objectMapper.createObjectNode();
                pNode.put("id", id);
                pNode.put("text", text);
                if (headingLevel > 0) {
                    pNode.put("style", "h" + headingLevel);
                    ObjectNode tocEntry = objectMapper.createObjectNode();
                    tocEntry.put("level", headingLevel);
                    tocEntry.put("title", text);
                    tocEntry.put("page", 1);
                    tocEntry.put("paragraphId", id);
                    toc.add(tocEntry);
                } else if (style != null && !style.isBlank()) {
                    pNode.put("style", style);
                }
                paragraphs.add(pNode);
                wordCount += countWords(text);
                // 图片占位:扫 XWPFRun 里嵌入的图
                imageCount += p.getRuns().stream()
                        .mapToInt(r -> r.getEmbeddedPictures() == null ? 0 : r.getEmbeddedPictures().size())
                        .sum();
            }
            // 表格:扁平化成 markdown-like 文本,每行一段
            for (XWPFTable table : doc.getTables()) {
                StringBuilder tableText = new StringBuilder();
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText() == null ? "" : cell.getText().replace("|", "/"));
                    }
                    tableText.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
                paraSeq++;
                ObjectNode pNode = objectMapper.createObjectNode();
                pNode.put("id", buildParagraphId(1, paraSeq, tableText.toString()));
                pNode.put("text", tableText.toString());
                pNode.put("style", "table");
                paragraphs.add(pNode);
                wordCount += countWords(tableText.toString());
            }
        }

        singlePage.set("paragraphs", paragraphs);
        pages.add(singlePage);

        root.put("totalPages", 1);
        root.put("wordCount", wordCount);
        root.put("strippedImageCount", imageCount);
        root.set("toc", toc);
        root.set("pages", pages);
        return root;
    }

    private int headingLevelFromStyle(String style) {
        if (style == null) return 0;
        String s = style.toLowerCase(Locale.ROOT);
        if (s.startsWith("heading")) {
            // "Heading1" / "heading 2" / "标题 1" 等
            for (int i = 1; i <= 6; i++) {
                if (s.contains(String.valueOf(i))) return i;
            }
            return 1;
        }
        if (s.startsWith("title")) return 1;
        return 0;
    }

    // ---------- .pdf ----------

    private ObjectNode parsePdf(byte[] bytes, String fileName) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sourceFile", fileName);
        root.put("format", "pdf");
        ArrayNode toc = objectMapper.createArrayNode();
        ArrayNode pages = objectMapper.createArrayNode();
        int wordCount = 0;
        int imageCount = 0;

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int total = doc.getNumberOfPages();
            for (int pageNo = 1; pageNo <= total; pageNo++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(doc);
                ObjectNode pageNode = objectMapper.createObjectNode();
                pageNode.put("pageNo", pageNo);
                ArrayNode paragraphs = objectMapper.createArrayNode();
                int paraSeq = 0;
                for (String para : splitParagraphs(pageText)) {
                    paraSeq++;
                    String id = buildParagraphId(pageNo, paraSeq, para);
                    ObjectNode pNode = objectMapper.createObjectNode();
                    pNode.put("id", id);
                    pNode.put("text", para);
                    // 启发式标题判断:短(<60 字符)+ 以数字编号开头 或 全是大写
                    int headingLevel = guessPdfHeadingLevel(para);
                    if (headingLevel > 0) {
                        pNode.put("style", "h" + headingLevel);
                        ObjectNode tocEntry = objectMapper.createObjectNode();
                        tocEntry.put("level", headingLevel);
                        tocEntry.put("title", para);
                        tocEntry.put("page", pageNo);
                        tocEntry.put("paragraphId", id);
                        toc.add(tocEntry);
                    }
                    paragraphs.add(pNode);
                    wordCount += countWords(para);
                }
                pageNode.set("paragraphs", paragraphs);
                pages.add(pageNode);
            }
            // 图片只统计页里资源对象数(粗略,准确做要走 PDResources 但开销大)
            imageCount = countPdfImages(doc);
            root.put("totalPages", total);
        }

        root.put("wordCount", wordCount);
        root.put("strippedImageCount", imageCount);
        root.set("toc", toc);
        root.set("pages", pages);
        return root;
    }

    private int countPdfImages(PDDocument doc) {
        int count = 0;
        try {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                var resources = doc.getPage(i).getResources();
                if (resources == null) continue;
                for (var name : resources.getXObjectNames()) {
                    try {
                        var xobj = resources.getXObject(name);
                        if (xobj instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                            count++;
                        }
                    } catch (Exception ignored) {
                        // 损坏的 xobject 跳过,不影响主流程
                    }
                }
            }
        } catch (Exception ignored) {
            // 资源扫描失败不影响主输出
        }
        return count;
    }

    private List<String> splitParagraphs(String pageText) {
        if (pageText == null || pageText.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        // PDFTextStripper 用空行分段最稳;同时按 \r\n / \n 兜底
        for (String chunk : pageText.split("\\R\\s*\\R")) {
            String trimmed = chunk.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed.replaceAll("\\s+", " "));
            }
        }
        // 如果整页没空行(常见,扫描型 PDF 的 text layer 经常一坨),退化按 \n 切
        if (out.isEmpty()) {
            for (String line : pageText.split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        }
        return out;
    }

    private int guessPdfHeadingLevel(String text) {
        if (text == null || text.isBlank()) return 0;
        String t = text.strip();
        if (t.length() > 80 || t.contains("。") || t.endsWith(".") && t.length() > 30) return 0;
        // 一级:一、二、三 / 第N章 / 第N部分
        if (t.matches("^[一二三四五六七八九十]+[、.,].*") || t.matches("^第[一二三四五六七八九十0-9]+(章|部分).*")) return 1;
        // 二级:1.1 / 1.2.3 风格
        if (t.matches("^\\d+\\.\\d+(?:\\.\\d+)?\\s+.*")) return 2;
        // 三级:1. / 2. 短行
        if (t.matches("^\\d+\\.\\s+.*") && t.length() < 40) return 3;
        return 0;
    }

    // ---------- common ----------

    /**
     * 段落 ID = "p{page}-{seq}-{shortHash}",同一文本同一位置每次跑生成同样 id,
     * Agent 二次定位某段时不会因重跑 normalize 而失效。
     */
    private String buildParagraphId(int pageNo, int seq, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(text == null ? new byte[0] : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(md.digest()).substring(0, 8);
            return "p" + pageNo + "-" + seq + "-" + hex;
        } catch (Exception e) {
            return "p" + pageNo + "-" + seq;
        }
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        // 中文按字算,空白切英文词
        int chinese = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) chinese++;
        }
        int english = text.replaceAll("[\\p{IsHan}]", "").trim().split("\\s+").length;
        return chinese + english;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
