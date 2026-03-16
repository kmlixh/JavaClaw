package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;

final class DatabaseConnectionSupport {

    private DatabaseConnectionSupport() {
    }

    static Connection openConnection(DataSource fallbackDataSource, JsonNode args) throws Exception {
        String jdbcUrl = args.path("jdbcUrl").asText("").trim();
        if (!jdbcUrl.isBlank()) {
            return DriverManager.getConnection(
                    jdbcUrl,
                    args.path("username").asText(""),
                    args.path("password").asText("")
            );
        }

        String dbType = args.path("dbType").asText("").trim().toLowerCase(Locale.ROOT);
        String host = args.path("host").asText("").trim();
        String database = args.path("database").asText("").trim();
        String username = args.path("username").asText("").trim();
        String password = args.path("password").asText("");
        int port = args.path("port").asInt(defaultPort(dbType));
        String schema = args.path("schema").asText("").trim();

        if (!dbType.isBlank() && !host.isBlank() && !database.isBlank() && !username.isBlank()) {
            String url = buildJdbcUrl(dbType, host, port, database);
            Connection connection = DriverManager.getConnection(url, username, password);
            if (!schema.isBlank()) {
                connection.setSchema(schema);
            }
            return connection;
        }

        return fallbackDataSource.getConnection();
    }

    private static String buildJdbcUrl(String dbType, String host, int port, String database) {
        return switch (dbType) {
            case "postgres", "postgresql" -> "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
            case "mysql" -> "jdbc:mysql://%s:%d/%s".formatted(host, port, database);
            case "sqlserver", "mssql" -> "jdbc:sqlserver://%s:%d;databaseName=%s".formatted(host, port, database);
            default -> throw new IllegalArgumentException("unsupported dbType: " + dbType);
        };
    }

    private static int defaultPort(String dbType) {
        return switch (dbType) {
            case "postgres", "postgresql" -> 5432;
            case "mysql" -> 3306;
            case "sqlserver", "mssql" -> 1433;
            default -> 0;
        };
    }
}
