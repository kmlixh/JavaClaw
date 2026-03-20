package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;

final class DatabaseConnectionSupport {

    record ConnectionTarget(
            Connection connection,
            String mode,
            String jdbcUrl,
            String schema
    ) {
    }

    private DatabaseConnectionSupport() {
    }

    static ConnectionTarget openConnection(DataSource fallbackDataSource, JsonNode args) throws Exception {
        String jdbcUrl = args.path("jdbcUrl").asText("").trim();
        if (!jdbcUrl.isBlank()) {
            return new ConnectionTarget(
                    DriverManager.getConnection(
                    jdbcUrl,
                    args.path("username").asText(""),
                    args.path("password").asText("")
                    ),
                    "explicit-jdbc-url",
                    jdbcUrl,
                    ""
            );
        }

        String dbType = args.path("dbType").asText("").trim().toLowerCase(Locale.ROOT);
        String host = args.path("host").asText("").trim();
        String database = args.path("database").asText("").trim();
        String username = args.path("username").asText("").trim();
        String password = args.path("password").asText("");
        int port = args.path("port").asInt(defaultPort(dbType));
        String schema = args.path("schema").asText("").trim();
        boolean hasExplicitConnectionFields = !host.isBlank()
                || !database.isBlank()
                || !username.isBlank()
                || !schema.isBlank()
                || args.hasNonNull("port");

        if (dbType.isBlank() && hasExplicitConnectionFields) {
            dbType = inferDbType(port);
        }

        if (!dbType.isBlank() && !host.isBlank() && !database.isBlank() && !username.isBlank()) {
            String url = buildJdbcUrl(dbType, host, port, database);
            Connection connection = DriverManager.getConnection(url, username, password);
            if (!schema.isBlank()) {
                connection.setSchema(schema);
            }
            return new ConnectionTarget(connection, "explicit-target", url, schema);
        }

        if (hasExplicitConnectionFields) {
            throw new IllegalArgumentException("incomplete remote database connection arguments; require dbType/host/database/username or jdbcUrl");
        }

        Connection connection = fallbackDataSource.getConnection();
        return new ConnectionTarget(
                connection,
                "fallback-datasource",
                safeUrl(connection),
                safeSchema(connection)
        );
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

    private static String inferDbType(int port) {
        return switch (port) {
            case 5432 -> "postgresql";
            case 3306 -> "mysql";
            case 1433 -> "sqlserver";
            default -> throw new IllegalArgumentException("dbType is missing and could not be inferred from port " + port);
        };
    }

    private static String safeUrl(Connection connection) {
        try {
            return connection.getMetaData().getURL();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (Exception ignored) {
            return "";
        }
    }
}
