package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TableRenderTool implements AgentTool {

    private final ObjectMapper objectMapper;

    public TableRenderTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "table.render";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Transform structured rows into a table block the frontend can render inline",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"columns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"dataJson\":{\"type\":\"string\"}},\"required\":[]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            JsonNode dataNode = args.path("dataJson");
            JsonNode source = (dataNode.isMissingNode() || dataNode.isNull() || dataNode.asText("").isBlank())
                    ? args
                    : objectMapper.readTree(dataNode.asText());
            List<String> columns = objectMapper.convertValue(source.path("columns"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<Map<String, Object>> rows = objectMapper.convertValue(source.path("rows"), objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            String title = args.path("title").asText(source.path("title").asText("数据表格"));
            return new ToolResult(
                    true,
                    "Rendered table with " + rows.size() + " rows",
                    objectMapper.writeValueAsString(Map.of(
                            "displayType", "table",
                            "title", title,
                            "columns", columns,
                            "rows", rows
                    )),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "table render failed", "{}", "[]", error.getMessage());
        }
    }
}
