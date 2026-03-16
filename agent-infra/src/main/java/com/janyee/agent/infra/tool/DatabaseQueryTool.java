package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DatabaseQueryTool implements AgentTool {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DatabaseQueryTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "db.query";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Execute a read-only SQL query. You can target either the default application database or pass explicit database connection info such as jdbcUrl/username/password or dbType/host/port/database/username/password.",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"},\"maxRows\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500},\"jdbcUrl\":{\"type\":\"string\"},\"dbType\":{\"type\":\"string\"},\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"database\":{\"type\":\"string\"},\"schema\":{\"type\":\"string\"},\"username\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"}},\"required\":[\"sql\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String sql = args.path("sql").asText("");
            int maxRows = Math.max(1, Math.min(500, args.path("maxRows").asInt(100)));
            if (sql.isBlank()) {
                return new ToolResult(false, "missing sql", "{}", "[]", "sql is required");
            }
            String normalized = sql.trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
                return new ToolResult(false, "query rejected", "{}", "[]", "db.query only allows SELECT/CTE statements");
            }
            try (Connection connection = DatabaseConnectionSupport.openConnection(dataSource, args);
                 Statement statement = connection.createStatement()) {
                statement.setMaxRows(maxRows);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        columns.add(metaData.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String column : columns) {
                            Object value = resultSet.getObject(column);
                            row.put(column, value);
                        }
                        rows.add(row);
                    }
                    return new ToolResult(
                            true,
                            "Query returned " + rows.size() + " rows",
                            objectMapper.writeValueAsString(Map.of(
                                    "displayType", "table",
                                    "columns", columns,
                                    "rows", rows,
                                    "sql", sql
                            )),
                            "[]",
                            null
                    );
                }
            }
        } catch (Exception error) {
            return new ToolResult(false, "query failed", "{}", "[]", error.getMessage());
        }
    }
}
