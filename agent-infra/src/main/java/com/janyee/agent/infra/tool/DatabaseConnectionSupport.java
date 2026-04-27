package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.janyee.agent.runtime.datasource.DatasourceResource;
import com.janyee.agent.runtime.datasource.DatasourceResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class DatabaseConnectionSupport {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionSupport.class);
    private static final Set<String> KNOWN_URL_SCHEMES = Set.of(
            "postgresql://", "postgres://", "mysql://", "mariadb://", "sqlserver://", "oracle:"
    );

    private static final ConnectionCredentialCache CREDENTIAL_CACHE = new ConnectionCredentialCache();

    record ConnectionTarget(
            Connection connection,
            String mode,
            String jdbcUrl,
            String schema
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            connection.close();
        }
    }

    private DatabaseConnectionSupport() {
    }

    static ConnectionTarget openConnection(DataSource fallbackDataSource, JsonNode args) throws Exception {
        return openConnection(fallbackDataSource, args, null);
    }

    /**
     * Credential resolution order when LLM omits username/password:
     *   1. Process-local {@link ConnectionCredentialCache} — prior successful connection in this run
     *   2. Backend {@link DatasourceResourceService} — skill-authored resource keyed by jdbcUrl
     *   3. Error — tell the LLM to retry with explicit credentials (last resort; ideally the
     *      skill + resource table cover every whitelisted jdbcUrl so this path never fires)
     */
    static ConnectionTarget openConnection(
            DataSource fallbackDataSource,
            JsonNode args,
            DatasourceResourceService resourceService
    ) throws Exception {
        String jdbcUrl = normalizeJdbcUrl(args.path("jdbcUrl").asText("").trim());
        if (!jdbcUrl.isBlank()) {
            String username = "";
            String password = "";
            // When the backend registry knows this jdbcUrl we ALWAYS prefer the registered
            // credentials over anything the LLM might have sent in args — the LLM should not
            // be managing secrets at all. Args-supplied creds only apply when no resource is
            // registered (e.g. ad-hoc read against an untracked DB).
            Optional<DatasourceResource> resource = resourceService == null
                    ? Optional.empty()
                    : resourceService.findByJdbcUrl(jdbcUrl);
            if (resource.isPresent()) {
                username = resource.get().username();
                password = resource.get().password();
                log.info("connection.resource_injected jdbcUrl={}, resourceId={}", jdbcUrl, resource.get().id());
            } else {
                username = args.path("username").asText("").trim();
                password = args.path("password").asText("");
                if (username.isBlank()) {
                    Optional<ConnectionCredentialCache.Credential> cached = CREDENTIAL_CACHE.get(jdbcUrl);
                    if (cached.isPresent()) {
                        username = cached.get().username();
                        password = cached.get().password();
                    }
                }
                if (username.isBlank()) {
                    throw new IllegalArgumentException(
                            "connection credentials missing for jdbcUrl=" + jdbcUrl
                                    + "; no backend datasource resource matches and no cache hit. "
                                    + "Register this jdbcUrl in the db_datasource table or supply "
                                    + "username/password in the tool call."
                    );
                }
            }

            Connection connection;
            try {
                connection = DriverManager.getConnection(jdbcUrl, username, password);
            } catch (Exception error) {
                // A failure after using cached credentials usually means the password rotated;
                // drop the cache entry so the next invocation forces the LLM to re-supply.
                CREDENTIAL_CACHE.invalidate(jdbcUrl);
                throw error;
            }
            CREDENTIAL_CACHE.put(jdbcUrl, username, password);
            return new ConnectionTarget(
                    connection,
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
        String schema = args.path("schema").asText("").trim();
        // Signals that the caller really wants an independent remote database. These are the
        // fields only meaningful for a standalone connection; database / schema / username
        // alone are hint-level and cannot by themselves establish a remote endpoint.
        boolean hasRemoteIntent = !host.isBlank() || !dbType.isBlank() || args.hasNonNull("port");
        boolean hasHintFields = !database.isBlank() || !schema.isBlank() || !username.isBlank();

        if (hasRemoteIntent) {
            if (dbType.isBlank()) {
                if (!args.hasNonNull("port")) {
                    throw new IllegalArgumentException(
                            "incomplete remote database connection: dbType is missing and no port was provided. "
                                    + "Supply jdbcUrl, or pass dbType together with host/database/username/password.");
                }
                int probedPort = args.path("port").asInt(0);
                try {
                    dbType = inferDbType(probedPort);
                } catch (IllegalArgumentException notRecognized) {
                    throw new IllegalArgumentException(
                            "dbType is missing and could not be inferred from port " + probedPort
                                    + "; supply dbType explicitly or use jdbcUrl.");
                }
            }
            int port = args.path("port").asInt(defaultPort(dbType));
            if (host.isBlank() || database.isBlank() || username.isBlank()) {
                throw new IllegalArgumentException(
                        "incomplete remote database connection arguments: host/database/username are required "
                                + "together with dbType/port, or supply a full jdbcUrl instead.");
            }
            String url = buildJdbcUrl(dbType, host, port, database);
            Connection connection;
            try {
                connection = DriverManager.getConnection(url, username, password);
            } catch (Exception error) {
                CREDENTIAL_CACHE.invalidate(url);
                throw error;
            }
            if (!schema.isBlank()) {
                connection.setSchema(schema);
            }
            CREDENTIAL_CACHE.put(url, username, password);
            return new ConnectionTarget(connection, "explicit-target", url, schema);
        }

        if (hasHintFields) {
            log.warn("connection.hint_fields_only database={}, schema={}, username_present={} — "
                            + "no host/port/dbType/jdbcUrl provided; routing to configured default datasource. "
                            + "If you meant a remote database, resubmit with jdbcUrl or dbType+host+database+username.",
                    database, schema, !username.isBlank());
        }

        // **Fallback policy**: LLM omitted jdbcUrl entirely. The old behaviour opened the
        // application's own system datasource (java_claw) — which is almost never what a
        // skill wants; its SQL would land on the agent's internal tables and fail loudly.
        //
        // Correct default: pick an enabled row from the db_datasource registry. Credentials
        // are injected server-side, keeping them out of the model loop. Only if the registry
        // is empty do we fall through to the system datasource (mostly for bootstrap / tests).
        if (resourceService != null) {
            Optional<DatasourceResource> defaultResource = resourceService.findDefault();
            if (defaultResource.isPresent()) {
                DatasourceResource resource = defaultResource.get();
                String url = resource.jdbcUrl();
                Connection connection;
                try {
                    connection = DriverManager.getConnection(url, resource.username(), resource.password());
                } catch (Exception error) {
                    CREDENTIAL_CACHE.invalidate(url);
                    throw error;
                }
                if (!schema.isBlank()) {
                    try {
                        connection.setSchema(schema);
                    } catch (Exception ignored) {
                        // Schema hint was advisory; proceed without it.
                    }
                }
                CREDENTIAL_CACHE.put(url, resource.username(), resource.password());
                log.info("connection.default_resource resourceId={}, jdbcUrl={}", resource.id(), url);
                return new ConnectionTarget(
                        connection,
                        "default-datasource-resource",
                        url,
                        schema
                );
            }
            log.warn("connection.default_resource_missing no enabled row in db_datasource; "
                    + "falling back to the system datasource. Register at least one row in "
                    + "db_datasource to route LLM queries away from the agent's internal DB.");
        }

        Connection connection = fallbackDataSource.getConnection();
        return new ConnectionTarget(
                connection,
                "fallback-datasource",
                safeUrl(connection),
                safeSchema(connection)
        );
    }

    // VisibleForTesting
    static ConnectionCredentialCache credentialCache() {
        return CREDENTIAL_CACHE;
    }

    /**
     * LLM tool calls occasionally drop the "jdbc:" scheme prefix and pass raw URLs like
     * "postgresql://host/db". DriverManager then fails with "No suitable driver found" even
     * though the driver is actually on the classpath. Detect that case and repair silently.
     */
    // VisibleForTesting
    static String normalizeJdbcUrl(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isBlank() || trimmed.startsWith("jdbc:")) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String scheme : KNOWN_URL_SCHEMES) {
            if (lower.startsWith(scheme)) {
                String fixed = "jdbc:" + trimmed;
                log.warn("jdbcUrl.auto_prefix original={}, normalized={}", trimmed, fixed);
                return fixed;
            }
        }
        return trimmed;
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
