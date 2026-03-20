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
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

@Component
public class DatabaseExecuteTool implements AgentTool {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DatabaseExecuteTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "db.execute";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Execute INSERT/UPDATE/DELETE/DDL SQL. You can target either the default application database or pass explicit database connection info such as jdbcUrl/username/password or dbType/host/port/database/username/password. This is high risk and should usually require approval.",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"},\"jdbcUrl\":{\"type\":\"string\"},\"dbType\":{\"type\":\"string\"},\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"database\":{\"type\":\"string\"},\"schema\":{\"type\":\"string\"},\"username\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"}},\"required\":[\"sql\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String sql = args.path("sql").asText("");
            if (sql.isBlank()) {
                return new ToolResult(false, "missing sql", "{}", "[]", "sql is required");
            }
            String normalized = sql.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("select") || normalized.startsWith("with")) {
                return new ToolResult(false, "statement rejected", "{}", "[]", "use db.query for SELECT statements");
            }
            DatabaseConnectionSupport.ConnectionTarget target = DatabaseConnectionSupport.openConnection(dataSource, args);
            try (Connection connection = target.connection();
                 Statement statement = connection.createStatement()) {
                int affected = statement.executeUpdate(sql);
                return new ToolResult(
                        true,
                        "Statement executed, affected rows: " + affected,
                        objectMapper.writeValueAsString(Map.of(
                                "displayType", "execute-result",
                                "affectedRows", affected,
                                "sql", sql
                        )),
                        "[]",
                        null
                );
            }
        } catch (Exception error) {
            return new ToolResult(false, "statement failed", "{}", "[]", error.getMessage());
        }
    }
}
