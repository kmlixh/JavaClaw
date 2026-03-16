package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EchartsOptionTool implements AgentTool {

    private final ObjectMapper objectMapper;

    public EchartsOptionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "chart.echarts";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Generate an ECharts option JSON from structured tabular data",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"chartType\":{\"type\":\"string\"},\"categoryField\":{\"type\":\"string\"},\"valueFields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"dataJson\":{\"type\":\"string\"}},\"required\":[\"categoryField\",\"valueFields\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            JsonNode source = objectMapper.readTree(args.path("dataJson").asText("{}"));
            List<Map<String, Object>> rows = objectMapper.convertValue(source.path("rows"), objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            String categoryField = args.path("categoryField").asText();
            List<String> valueFields = objectMapper.convertValue(args.path("valueFields"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            String chartType = args.path("chartType").asText("bar");
            String title = args.path("title").asText("图表");
            List<Object> categories = rows.stream().map(row -> row.get(categoryField)).toList();
            List<Map<String, Object>> series = new ArrayList<>();
            for (String field : valueFields) {
                series.add(Map.of(
                        "name", field,
                        "type", chartType,
                        "data", rows.stream().map(row -> row.get(field)).toList()
                ));
            }
            Map<String, Object> option = Map.of(
                    "title", Map.of("text", title),
                    "tooltip", Map.of("trigger", "axis"),
                    "legend", Map.of(),
                    "xAxis", Map.of("type", "category", "data", categories),
                    "yAxis", Map.of("type", "value"),
                    "series", series
            );
            return new ToolResult(
                    true,
                    "Generated ECharts option with " + rows.size() + " rows",
                    objectMapper.writeValueAsString(Map.of(
                            "displayType", "echarts",
                            "title", title,
                            "option", option
                    )),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "chart generation failed", "{}", "[]", error.getMessage());
        }
    }
}
