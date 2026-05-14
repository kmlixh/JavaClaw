package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.datasource.DatasourceResourceService;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.runtime.skill.SkillGuardStore;
import com.janyee.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DatabaseSchemaInspectTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInspectTool.class);
    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int SCHEMA_HARD_LIMIT = 2048;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SkillGuardStore guardStore;
    private final DatasourceResourceService datasourceResourceService;
    private final Map<String, CachedSchema> schemaCache = new ConcurrentHashMap<>();
    private final Map<String, CachedDataSourceInfo> dataSourceInfoCache = new ConcurrentHashMap<>();

    public DatabaseSchemaInspectTool(
            DataSource dataSource,
            ObjectMapper objectMapper,
            SkillGuardStore guardStore,
            DatasourceResourceService datasourceResourceService
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.guardStore = guardStore;
        this.datasourceResourceService = datasourceResourceService;
    }

    @Override
    public String name() {
        return "db.schema.inspect";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Inspect table schema with complete columns, JDBC types, primary keys and indexes. Use this tool for structure discovery instead of probing with db.query.",
                """
                {"type":"object","properties":{
                  "table":{"type":"string","description":"Table name, optionally schema-qualified, for example public.users"},
                  "schema":{"type":"string","description":"Default schema when table is not schema-qualified"},
                  "jdbcUrl":{"type":"string","description":"Explicit JDBC URL; omit to use application datasource"},
                  "dbType":{"type":"string","enum":["postgresql","postgres","mysql","sqlserver","mssql"]},
                  "host":{"type":"string"},
                  "port":{"type":"integer"},
                  "database":{"type":"string"},
                  "forceRefresh":{"type":"boolean","description":"Bypass cached schema and refresh metadata"}
                },"required":["table"]}
                """
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String rawTable = args.path("table").asText("").trim();
            if (rawTable.isBlank()) {
                return new ToolResult(false, "missing table", "{}", "[]", "table is required");
            }
            String defaultSchema = blankToNull(args.path("schema").asText(""));
            QualifiedName table = parseQualifiedName(rawTable, defaultSchema);
            boolean forceRefresh = args.path("forceRefresh").asBoolean(false);
            log.info("db.schema.inspect.start runId={}, requestedTable={}, requestedSchema={}, forceRefresh={}",
                    invocation.runId(), table.table(), table.schema(), forceRefresh);

            SkillGuard guard = guardStore.find(invocation.runId()).orElse(SkillGuard.NONE);
            if (guard.hasTableEnforcement()) {
                SkillGuard.TableCheck check = guard.checkTable(table.schema(), table.table());
                if (!check.allowed()) {
                    return rejectByWhitelist(invocation.runId(), guard, table, check.reason());
                }
            }

            try (DatabaseConnectionSupport.ConnectionTarget target = DatabaseConnectionSupport.openConnection(dataSource, args, datasourceResourceService)) {
                Connection connection = target.connection();
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = connection.getCatalog();
                DataSourceInfo dataSourceInfo = inspectDataSource(connection, target);
                log.info("db.schema.inspect.datasource runId={}, mode={}, jdbcUrl={}, catalog={}, currentSchema={}, product={}",
                        invocation.runId(),
                        dataSourceInfo.mode(),
                        dataSourceInfo.jdbcUrl(),
                        dataSourceInfo.catalog(),
                        dataSourceInfo.schema(),
                        dataSourceInfo.databaseProduct());
                QualifiedName resolvedTable = resolveTable(metaData, catalog, table, connection);
                if (resolvedTable == null) {
                    return tableNotFound(invocation.runId(), metaData, catalog, table, dataSourceInfo);
                }

                String cacheKey = cacheKey(dataSourceInfo, resolvedTable);
                CachedSchema cached = schemaCache.get(cacheKey);
                if (!forceRefresh && cached != null && !cached.isExpired()) {
                    log.info("db.schema.inspect.cache_hit runId={}, requested={}, resolved={}, summary={}, dataJson={}",
                            invocation.runId(),
                            qualifiedName(table),
                            qualifiedName(resolvedTable),
                            cached.summary(),
                            cached.dataJson());
                    return new ToolResult(
                            true,
                            cached.summary() + " (cached)",
                            cached.dataJson(),
                            "[]",
                            null
                    );
                }

                List<Map<String, Object>> columnRows = readColumns(metaData, catalog, resolvedTable);
                Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, resolvedTable);
                List<Map<String, Object>> indexes = readIndexes(metaData, catalog, resolvedTable);
                PartitionInspector.PartitionInfo partitionInfo = PartitionInspector.inspect(
                        connection, dataSourceInfo.jdbcUrl(), resolvedTable.schema(), resolvedTable.table());
                Map<String, Object> metadata = linkedMapOf(
                        "inspectionTool", name(),
                        "cacheKey", cacheKey,
                        "cacheHit", false,
                        "cacheTtlMillis", CACHE_TTL_MILLIS,
                        "schema", resolvedTable.schema(),
                        "table", resolvedTable.table(),
                        "qualifiedName", qualifiedName(resolvedTable),
                        "requestedSchema", table.schema(),
                        "requestedTable", table.table(),
                        "primaryKeys", primaryKeys,
                        "indexes", indexes,
                        "partition", partitionInfo.toMap(),
                        "dataSource", dataSourceInfo.toMap(),
                        "complete", true
                );
                String dataJson = objectMapper.writeValueAsString(linkedMapOf(
                        "displayType", "table",
                        "columns", List.of(
                                "column_name",
                                "data_type",
                                "nullable",
                                "is_primary_key",
                                "remarks"
                        ),
                        "rows", columnRows,
                        "metadata", metadata
                ));
                // 不再自动 rewrite，分区表的"取哪个 child"由 AI 自己决定。摘要里把决策需要的信息都给齐：
                // 策略、键列、键类型、可查范围（earliest → latest），AI 据此写 WHERE 过滤。
                String partitionSuffix = partitionInfo.isPartitioned()
                        ? "; partitioned (" + partitionInfo.partitionStrategy() + ") on "
                                + partitionInfo.partitionKey() + " " + partitionInfo.partitionKeyType()
                                + "; children=" + partitionInfo.childCount()
                                + ", earliest=" + partitionInfo.earliestPartition()
                                + ", latest=" + partitionInfo.latestPartition()
                                + " — write a WHERE filter on " + partitionInfo.partitionKey()
                                + " to scope the query (no filter = full scan across all children)"
                        : "";
                String summary = "Schema inspection returned %d columns for %s%s".formatted(
                        columnRows.size(), qualifiedName(resolvedTable), partitionSuffix);
                schemaCache.put(cacheKey, new CachedSchema(System.currentTimeMillis(), summary, dataJson));
                log.info("db.schema.inspect.result runId={}, requested={}, resolved={}, columnCount={}, columns={}, primaryKeys={}, indexCount={}, dataJson={}",
                        invocation.runId(),
                        qualifiedName(table),
                        qualifiedName(resolvedTable),
                        columnRows.size(),
                        columnNames(columnRows),
                        primaryKeys,
                        indexes.size(),
                        dataJson);

                return new ToolResult(
                        true,
                        summary,
                        dataJson,
                        "[]",
                        null
                );
            }
        } catch (Exception error) {
            log.warn("db.schema.inspect.error runId={}, error={}", invocation.runId(), error.getMessage(), error);
            return new ToolResult(false, "schema inspection failed", "{}", "[]", error.getMessage());
        }
    }

    private ToolResult rejectByWhitelist(String runId, SkillGuard guard, QualifiedName requested, String reason) throws Exception {
        String qualified = qualifiedName(requested);
        log.warn("db.schema.inspect.whitelist_rejected runId={}, requested={}, reason={}, whitelist={}",
                runId, qualified, reason, guard.whitelistTables());
        String message = String.format(
                "Refusing schema.inspect for '%s': %s. "
                        + "The active skill restricts db.schema.inspect to its whitelist. "
                        + "Pick one of the allowed tables and retry.",
                qualified.isEmpty() ? requested.table() : qualified,
                reason
        );
        return new ToolResult(
                false,
                "schema inspect rejected by skill whitelist",
                objectMapper.writeValueAsString(linkedMapOf(
                        "displayType", "tool_error",
                        "tool", name(),
                        "recoverable", true,
                        "retryRecommended", true,
                        "reason", "skill_whitelist_denied",
                        "requestedSchema", requested.schema(),
                        "requestedTable", requested.table(),
                        "whitelistTables", new ArrayList<>(guard.whitelistTables()),
                        "skills", guard.contributingSkills(),
                        "message", message
                )),
                "[]",
                message
        );
    }

    private DataSourceInfo inspectDataSource(Connection connection, DatabaseConnectionSupport.ConnectionTarget target) throws Exception {
        String fingerprint = dataSourceFingerprint(connection, target);
        CachedDataSourceInfo cached = dataSourceInfoCache.get(fingerprint);
        if (cached != null && !cached.isExpired()) {
            return cached.info();
        }
        DatabaseMetaData metaData = connection.getMetaData();
        DataSourceInfo info = new DataSourceInfo(
                target.mode(),
                fingerprint,
                sanitizeJdbcUrl(target.jdbcUrl()),
                blankToNull(connection.getCatalog()),
                blankToNull(safeSchema(connection)),
                blankToNull(metaData.getDatabaseProductName()),
                blankToNull(metaData.getDatabaseProductVersion()),
                blankToNull(metaData.getDriverName()),
                blankToNull(metaData.getDriverVersion())
        );
        dataSourceInfoCache.put(fingerprint, new CachedDataSourceInfo(System.currentTimeMillis(), info));
        return info;
    }

    private QualifiedName resolveTable(DatabaseMetaData metaData, String catalog, QualifiedName requested, Connection connection) throws Exception {
        List<QualifiedName> candidates = new ArrayList<>();
        if (hasColumns(metaData, catalog, requested)) {
            return requested;
        }
        String connectionSchema = blankToNull(safeSchema(connection));
        if (requested.schema() == null && connectionSchema != null) {
            QualifiedName withConnectionSchema = new QualifiedName(connectionSchema, requested.table());
            if (hasColumns(metaData, catalog, withConnectionSchema)) {
                return withConnectionSchema;
            }
        }
        try (ResultSet tables = metaData.getTables(catalog, requested.schema(), requested.table(), new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) {
                QualifiedName candidate = new QualifiedName(
                        blankToNull(tables.getString("TABLE_SCHEM")),
                        tables.getString("TABLE_NAME")
                );
                if (hasColumns(metaData, catalog, candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private boolean hasColumns(DatabaseMetaData metaData, String catalog, QualifiedName table) throws Exception {
        try (ResultSet columns = metaData.getColumns(catalog, table.schema(), table.table(), "%")) {
            return columns.next();
        }
    }

    private ToolResult tableNotFound(String runId, DatabaseMetaData metaData, String catalog, QualifiedName table, DataSourceInfo dataSourceInfo) throws Exception {
        List<Map<String, Object>> candidates = findSimilarTables(metaData, catalog, table);
        log.warn("db.schema.inspect.not_found runId={}, requested={}, dataSource={}, candidateCount={}, candidates={}",
                runId,
                qualifiedName(table),
                dataSourceInfo.toMap(),
                candidates.size(),
                candidates);
        // Both "fallback-datasource" (system DB) and "default-datasource-resource" (first
        // enabled db_datasource row) mean the tool call did NOT carry an explicit jdbcUrl.
        // The remediation hint is the same — re-send with jdbcUrl.
        boolean fellBackToDefault = "fallback-datasource".equals(dataSourceInfo.mode())
                || "default-datasource-resource".equals(dataSourceInfo.mode());
        String humanMessage = fellBackToDefault
                ? String.format(
                        "table %s.%s not found in the default datasource (%s). "
                                + "This tool call did NOT carry jdbcUrl, so it landed on the configured "
                                + "default from db_datasource. If the target table lives in a different "
                                + "database, retry with jdbcUrl set explicitly.",
                        table.schema() == null ? "(default)" : table.schema(),
                        table.table(),
                        dataSourceInfo.jdbcUrl())
                : String.format(
                        "table %s.%s not found in %s. Verify the schema + table name against the tables "
                                + "your skill whitelist allows; do not fall back to ad-hoc tables.",
                        table.schema() == null ? "(default)" : table.schema(),
                        table.table(),
                        dataSourceInfo.jdbcUrl());
        return new ToolResult(
                false,
                "schema not found",
                objectMapper.writeValueAsString(linkedMapOf(
                        "displayType", "tool_error",
                        "tool", name(),
                        "message", humanMessage,
                        "recoverable", true,
                        "retryRecommended", true,
                        "fellBackToDefault", fellBackToDefault,
                        "requestedSchema", table.schema(),
                        "requestedTable", table.table(),
                        "dataSource", dataSourceInfo.toMap(),
                        "candidateTables", candidates
                )),
                "[]",
                humanMessage
        );
    }

    private List<Map<String, Object>> readColumns(DatabaseMetaData metaData, String catalog, QualifiedName table) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, table);
        try (ResultSet columns = metaData.getColumns(catalog, table.schema(), table.table(), "%")) {
            while (columns.next() && rows.size() < SCHEMA_HARD_LIMIT) {
                String columnName = columns.getString("COLUMN_NAME");
                int jdbcTypeCode = columns.getInt("DATA_TYPE");
                String vendorTypeName = columns.getString("TYPE_NAME");
                rows.add(linkedMapOf(
                        "column_name", columnName,
                        "data_type", normalizeJdbcTypeName(jdbcTypeCode, vendorTypeName),
                        "nullable", nullableLabel(columns.getInt("NULLABLE")),
                        "is_primary_key", primaryKeys.contains(columnName == null ? "" : columnName.toLowerCase(Locale.ROOT)),
                        "remarks", blankToNull(columns.getString("REMARKS"))
                ));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> findSimilarTables(DatabaseMetaData metaData, String catalog, QualifiedName table) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        String pattern = table.table() == null || table.table().isBlank() ? "%" : "%" + table.table() + "%";
        try (ResultSet tables = metaData.getTables(catalog, table.schema(), pattern, new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && rows.size() < 20) {
                rows.add(linkedMapOf(
                        "table_schema", blankToNull(tables.getString("TABLE_SCHEM")),
                        "table_name", tables.getString("TABLE_NAME"),
                        "table_type", tables.getString("TABLE_TYPE"),
                        "remarks", blankToNull(tables.getString("REMARKS"))
                ));
            }
        }
        if (!rows.isEmpty()) {
            return rows;
        }
        try (ResultSet tables = metaData.getTables(catalog, table.schema(), "%", new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && rows.size() < 20) {
                rows.add(linkedMapOf(
                        "table_schema", blankToNull(tables.getString("TABLE_SCHEM")),
                        "table_name", tables.getString("TABLE_NAME"),
                        "table_type", tables.getString("TABLE_TYPE"),
                        "remarks", blankToNull(tables.getString("REMARKS"))
                ));
            }
        }
        return rows;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metaData, String catalog, QualifiedName table) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet pk = metaData.getPrimaryKeys(catalog, table.schema(), table.table())) {
            while (pk.next()) {
                String column = pk.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    keys.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    private List<Map<String, Object>> readIndexes(DatabaseMetaData metaData, String catalog, QualifiedName table) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet rs = metaData.getIndexInfo(catalog, table.schema(), table.table(), false, false)) {
            while (rs.next()) {
                String indexName = blankToNull(rs.getString("INDEX_NAME"));
                String columnName = blankToNull(rs.getString("COLUMN_NAME"));
                if (indexName == null || columnName == null) {
                    continue;
                }
                rows.add(linkedMapOf(
                        "index_name", indexName,
                        "column_name", columnName,
                        "non_unique", rs.getBoolean("NON_UNIQUE"),
                        "asc_or_desc", blankToNull(rs.getString("ASC_OR_DESC"))
                ));
            }
        }
        return rows;
    }

    private String normalizeJdbcTypeName(int jdbcTypeCode, String vendorTypeName) {
        try {
            return JDBCType.valueOf(jdbcTypeCode).getName();
        } catch (Exception ignored) {
            return blankToNull(vendorTypeName);
        }
    }

    private String nullableLabel(int nullable) {
        return switch (nullable) {
            case DatabaseMetaData.columnNoNulls -> "NO";
            case DatabaseMetaData.columnNullable -> "YES";
            default -> "UNKNOWN";
        };
    }

    private int safeInt(ResultSet resultSet, String column) {
        try {
            if (!hasResultSetColumn(resultSet, column)) {
                return 0;
            }
            return resultSet.getInt(column);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String safeString(ResultSet resultSet, String column) {
        try {
            if (!hasResultSetColumn(resultSet, column)) {
                return null;
            }
            return blankToNull(resultSet.getString(column));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasResultSetColumn(ResultSet resultSet, String column) throws Exception {
        ResultSetMetaData metaData = resultSet.getMetaData();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            if (column.equalsIgnoreCase(metaData.getColumnName(index))) {
                return true;
            }
        }
        return false;
    }

    private QualifiedName parseQualifiedName(String raw, String defaultSchema) {
        String cleaned = cleanIdentifier(raw);
        String[] parts = cleaned.split("\\.");
        if (parts.length >= 2) {
            return new QualifiedName(parts[parts.length - 2], parts[parts.length - 1]);
        }
        return new QualifiedName(blankToNull(defaultSchema), cleaned);
    }

    private String cleanIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String dataSourceFingerprint(Connection connection, DatabaseConnectionSupport.ConnectionTarget target) {
        String url = sanitizeJdbcUrl(target.jdbcUrl());
        String schema = blankToNull(target.schema()) != null ? target.schema() : safeSchema(connection);
        return (target.mode() + "|" + url + "|" + blankToNull(schema)).toLowerCase(Locale.ROOT);
    }

    private String cacheKey(DataSourceInfo info, QualifiedName table) {
        return (info.fingerprint() + "|" + qualifiedName(table)).toLowerCase(Locale.ROOT);
    }

    private String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        return jdbcUrl.replaceAll("(?i)(password|pwd)=([^;&]+)", "$1=***");
    }

    private String qualifiedName(QualifiedName name) {
        if (name == null) {
            return "";
        }
        return (name.schema() == null || name.schema().isBlank())
                ? name.table()
                : name.schema() + "." + name.table();
    }

    private Map<String, Object> linkedMapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private List<String> columnNames(List<Map<String, Object>> columnRows) {
        return columnRows.stream()
                .map((row) -> String.valueOf(row.get("column_name")))
                .toList();
    }

    private record QualifiedName(String schema, String table) {
    }

    private record CachedSchema(long createdAtMillis, String summary, String dataJson) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMillis > CACHE_TTL_MILLIS;
        }
    }

    private record CachedDataSourceInfo(long createdAtMillis, DataSourceInfo info) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMillis > CACHE_TTL_MILLIS;
        }
    }

    private record DataSourceInfo(
            String mode,
            String fingerprint,
            String jdbcUrl,
            String catalog,
            String schema,
            String databaseProduct,
            String databaseVersion,
            String driverName,
            String driverVersion
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mode", mode);
            map.put("fingerprint", fingerprint);
            map.put("jdbcUrl", jdbcUrl);
            map.put("catalog", catalog);
            map.put("schema", schema);
            map.put("databaseProduct", databaseProduct);
            map.put("databaseVersion", databaseVersion);
            map.put("driverName", driverName);
            map.put("driverVersion", driverVersion);
            return map;
        }
    }
}
