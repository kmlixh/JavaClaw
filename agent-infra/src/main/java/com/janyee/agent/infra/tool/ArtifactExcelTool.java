package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.tool.AgentTool;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Component
public class ArtifactExcelTool implements AgentTool {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ArtifactExcelTool(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "artifact.excel";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Generate a downloadable Excel workbook (.xlsx) from columns and rows",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"sheetName\":{\"type\":\"string\"},\"columns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},\"required\":[\"name\",\"columns\",\"rows\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            List<String> columns = objectMapper.convertValue(args.path("columns"), new TypeReference<>() {});
            List<Map<String, Object>> rows = objectMapper.convertValue(args.path("rows"), new TypeReference<>() {});
            String name = ensureExtension(args.path("name").asText("report.xlsx"), ".xlsx");
            byte[] bytes = createWorkbook(args.path("sheetName").asText("Sheet1"), columns, rows);
            ArtifactRecord artifact = artifactService.saveBinaryArtifact(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    "excel",
                    name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bytes
            );
            return artifactResult("Generated Excel workbook " + artifact.name(), artifact);
        } catch (Exception error) {
            return new ToolResult(false, "excel generation failed", "{}", "[]", error.getMessage());
        }
    }

    private byte[] createWorkbook(String sheetName, List<String> columns, List<Map<String, Object>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName);
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                Map<String, Object> values = rows.get(rowIndex);
                for (int colIndex = 0; colIndex < columns.size(); colIndex++) {
                    Object value = values.get(columns.get(colIndex));
                    row.createCell(colIndex).setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
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
