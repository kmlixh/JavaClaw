package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.datasource.DatasourceResourceService;
import com.janyee.agent.runtime.loop.PlanStatus;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.loop.RunPlanStore;
import com.janyee.agent.runtime.skill.PlanStepRule;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DatabaseQueryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(DatabaseQueryTool.class);

    private static final Pattern DESCRIBE_PATTERN = Pattern.compile("(?is)^\\s*(?:describe|desc)\\s+(.+?)\\s*;?\\s*$");
    private static final Pattern SHOW_COLUMNS_PATTERN = Pattern.compile("(?is)^\\s*show\\s+columns\\s+from\\s+([\\w\"`.$]+)(?:\\s+from\\s+([\\w\"`.$]+))?\\s*;?\\s*$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?is)^\\s*show\\s+tables(?:\\s+from\\s+([\\w\"`.$]+))?\\s*;?\\s*$");
    private static final Pattern FROM_OR_JOIN_PATTERN = Pattern.compile("(?is)\\b(?:from|join)\\s+([\\w\"`.$]+)");
    private static final Pattern UNDEFINED_COLUMN_PATTERN = Pattern.compile("(?is)column\\s+\"([^\"]+)\"\\s+does not exist");
    private static final Pattern PG_SUGGEST_COLUMN_PATTERN = Pattern.compile("(?is)Perhaps you meant to reference the column\\s+\"([^\"]+)\"");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SkillGuardStore guardStore;
    private final DatasourceResourceService datasourceResourceService;
    private final RunPlanStore planStore;

    public DatabaseQueryTool(
            DataSource dataSource,
            ObjectMapper objectMapper,
            SkillGuardStore guardStore,
            DatasourceResourceService datasourceResourceService,
            RunPlanStore planStore
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.guardStore = guardStore;
        this.datasourceResourceService = datasourceResourceService;
        this.planStore = planStore;
    }

    @Override
    public String name() {
        return "db.query";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Execute read-only business SELECT/CTE SQL. You can target either the default application database or pass explicit database connection info such as jdbcUrl/username/password or dbType/host/port/database/username/password. Do not use this tool for table schema inspection: DESCRIBE, SHOW COLUMNS, information_schema.columns, pg_catalog column queries, and SELECT * LIMIT 1 schema probes are forbidden. Use db.schema.inspect for table columns, primary keys and indexes.",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"},\"maxRows\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500},\"jdbcUrl\":{\"type\":\"string\",\"description\":\"Target database URL. Credentials are auto-injected by the backend db_datasource registry; do not pass username/password.\"},\"dbType\":{\"type\":\"string\"},\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"database\":{\"type\":\"string\"},\"schema\":{\"type\":\"string\"}},\"required\":[\"sql\"]}"
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
            String defaultSchema = blankToNull(args.path("schema").asText(""));
            sql = normalizeSqlBeforeExecution(sql, defaultSchema);

            SkillGuard guard = guardStore.find(invocation.runId()).orElse(SkillGuard.NONE);
            // Hard-gate: skill requires a plan but no step is IN_PROGRESS → reject so LLM has to
            // plan.update to IN_PROGRESS first. Without this gate, LLM can run 20 freestyle
            // queries before artifact time blows up with "skill requires a plan with steps..."
            // which is exactly what happened in session 2e6e9f2c.
            ToolResult planGated = enforcePlanInProgress(invocation.runId(), guard);
            if (planGated != null) {
                return planGated;
            }
            if (guard.hasTableEnforcement()) {
                ToolResult rejected = enforceWhitelist(invocation.runId(), guard, sql, defaultSchema);
                if (rejected != null) {
                    return rejected;
                }
            }

            try (DatabaseConnectionSupport.ConnectionTarget target = DatabaseConnectionSupport.openConnection(dataSource, args, datasourceResourceService)) {
                Connection connection = target.connection();
                String normalized = sql.trim().toLowerCase(Locale.ROOT);
                log.info("db.query.target runId={}, mode={}, jdbcUrl={}, schema={}, sql={}",
                        invocation.runId(),
                        target.mode(),
                        target.jdbcUrl(),
                        target.schema(),
                        sql);

                ToolResult blockedSchemaProbe = rejectTableSchemaProbe(sql, normalized, args, target.schema());
                if (blockedSchemaProbe != null) {
                    return blockedSchemaProbe;
                }

                ToolResult metadataResult = tryMetadataShortcut(connection, sql, normalized, target.schema(), maxRows);
                if (metadataResult != null) {
                    return metadataResult;
                }

                // 不再自动重写"父表 → 最新子分区"。AI 在 skill 引导下负责自己写 WHERE 过滤分区键，
                // PG native partition pruning 会落到正确的 child。没写分区过滤 = 全表扫描，由 SQL 行为
                // 自然反馈给 AI（慢 / 超时），不再后端兜底改写。
                if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
                    return buildRecoverableFailure(
                            connection,
                            sql,
                            target.schema(),
                            "query rejected",
                            "query_rejected",
                            null,
                            true,
                            List.of(
                                    "db.query only allows SELECT/CTE business queries.",
                                    "For table structure inspection, call db.schema.inspect with table/schema and the same connection arguments.",
                                    "For table enumeration only, use SHOW TABLES or a SELECT against information_schema.tables."
                            ),
                            List.of()
                    );
                }

                return executeQuery(connection, sql, maxRows);
            }
        } catch (Exception error) {
            return new ToolResult(false, "query failed", "{}", "[]", error.getMessage());
        }
    }

    /**
     * If the skill requires a plan, this call is only allowed when a step is IN_PROGRESS.
     * Otherwise the LLM is still in "freestyle" mode and will end up running 10+ unrelated
     * queries before guard blocks the artifact. Returning a recoverable error with the next
     * PENDING stepId nudges LLM to call plan.update first.
     */
    private ToolResult enforcePlanInProgress(String runId, SkillGuard guard) throws Exception {
        if (runId == null || runId.isBlank() || guard == null || !guard.hasPlanEnforcement()) {
            return null;
        }
        RunPlan plan = planStore.find(runId).orElse(null);
        if (plan == null || plan.isEmpty()) {
            // plan auto-seed should have filled it by now. If it didn't, something's broken;
            // fall through and let whitelist enforcement surface the table violations instead.
            return null;
        }
        if (plan.currentInProgressStep().isPresent()) {
            return null;
        }
        // No step IN_PROGRESS — find the next PENDING step to nudge the LLM toward.
        PlanStep nextPending = plan.steps().stream()
                .filter(s -> s.status() == PlanStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (nextPending == null) {
            // All steps COMPLETED/FAILED/SKIPPED and LLM is still trying to query — let it
            // through; the whitelist / completion state will sort it out.
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "tool_error");
        data.put("tool", name());
        data.put("recoverable", true);
        data.put("retryRecommended", true);
        data.put("reason", "plan_step_not_in_progress");
        data.put("nextPendingStepId", nextPending.id());
        data.put("nextPendingStepTitle", nextPending.title());
        String message = "No plan step is IN_PROGRESS. Before issuing db.query, call "
                + "plan.update with {\"stepId\":\"" + nextPending.id() + "\", \"status\":\"IN_PROGRESS\"} "
                + "to move the plan forward. The queries you issue then contribute to that step.";
        data.put("message", message);
        log.warn("db.query.plan_not_in_progress runId={}, nextPendingStepId={}", runId, nextPending.id());
        return new ToolResult(
                false,
                "query rejected: plan step not IN_PROGRESS",
                objectMapper.writeValueAsString(data),
                "[]",
                message
        );
    }

    private ToolResult enforceWhitelist(String runId, SkillGuard guard, String sql, String defaultSchema) throws Exception {
        Set<SqlTableExtractor.Ref> refs = SqlTableExtractor.extract(sql);
        if (refs.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        for (SqlTableExtractor.Ref ref : refs) {
            String refSchema = (ref.schema() == null || ref.schema().isEmpty()) ? defaultSchema : ref.schema();
            SkillGuard.TableCheck check = guard.checkTable(refSchema, ref.table());
            if (check.allowed()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("schema", refSchema == null ? "" : refSchema);
            entry.put("table", ref.table());
            entry.put("reason", check.reason());
            violations.add(entry);
        }
        if (violations.isEmpty()) {
            return null;
        }
        // 把当前 plan 的 IN_PROGRESS step 上挂的 SQL 模板、允许表、jdbcUrl 一并回给 LLM —— LLM
        // 不用再去猜列名,直接抄模板。
        CurrentStepContext currentStep = resolveCurrentStepContext(runId, guard);
        String message = "SQL touches tables outside the skill whitelist: " + violations
                + ". Rewrite the query to use only whitelisted schema.table names.";
        if (currentStep != null && currentStep.hasTemplates()) {
            message += " Copy one of the provided sqlTemplates verbatim (only replace {{city}}/{{county}}/{{geometry_json}} placeholders).";
        }
        log.warn("db.query.whitelist_rejected runId={}, violations={}, whitelist={}, currentStep={}",
                runId, violations, guard.whitelistTables(), currentStep == null ? "(none)" : currentStep.stepId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "tool_error");
        data.put("tool", name());
        data.put("recoverable", true);
        data.put("retryRecommended", true);
        data.put("reason", "skill_whitelist_denied");
        data.put("violations", violations);
        data.put("whitelistTables", new ArrayList<>(guard.whitelistTables()));
        data.put("skills", guard.contributingSkills());
        if (currentStep != null) {
            Map<String, Object> stepHint = new LinkedHashMap<>();
            stepHint.put("stepId", currentStep.stepId);
            stepHint.put("stepTitle", currentStep.stepTitle);
            if (!currentStep.jdbcUrl.isBlank()) {
                stepHint.put("jdbcUrl", currentStep.jdbcUrl);
            }
            if (!currentStep.allowedTables.isEmpty()) {
                stepHint.put("tableAllowList", currentStep.allowedTables);
            }
            if (!currentStep.sqlTemplatesGeoJson.isEmpty()) {
                stepHint.put("sqlTemplatesGeoJson", currentStep.sqlTemplatesGeoJson);
            }
            if (!currentStep.sqlTemplates.isEmpty()) {
                stepHint.put("sqlTemplates", currentStep.sqlTemplates);
            }
            if (!currentStep.sqlTemplatesNoFilter.isEmpty()) {
                stepHint.put("sqlTemplatesNoFilter", currentStep.sqlTemplatesNoFilter);
            }
            data.put("currentStep", stepHint);
        } else if (!guard.requiredPlanStepIds().isEmpty()) {
            data.put("currentStep", Map.of(
                    "note", "No plan step is IN_PROGRESS. Call plan.update to move the next PENDING step to IN_PROGRESS first, then reissue the query."
            ));
        }
        data.put("message", message);
        data.put("sql", sql);
        return new ToolResult(
                false,
                "query rejected by skill whitelist",
                objectMapper.writeValueAsString(data),
                "[]",
                message
        );
    }

    /**
     * Find the currently IN_PROGRESS plan step for this run (if any) and assemble a summary
     * of the SQL templates / allowed tables / jdbcUrl declared for that step. Returned value
     * is shaped for JSON serialization; fields that don't apply are left blank.
     */
    private CurrentStepContext resolveCurrentStepContext(String runId, SkillGuard guard) {
        if (runId == null || runId.isBlank() || planStore == null) {
            return null;
        }
        RunPlan plan = planStore.find(runId).orElse(null);
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        PlanStep step = plan.currentInProgressStep()
                .or(() -> plan.steps().stream()
                        .filter(s -> s.status() == PlanStatus.PENDING)
                        .findFirst())
                .orElse(null);
        if (step == null) {
            return null;
        }
        PlanStepRule rule = guard.stepRule(step.id()).orElse(null);
        CurrentStepContext ctx = new CurrentStepContext();
        ctx.stepId = step.id();
        ctx.stepTitle = step.title();
        ctx.jdbcUrl = step.jdbcUrl();
        ctx.allowedTables = step.allowedTables();
        ctx.sqlTemplates = rule == null ? List.of() : rule.sqlTemplates();
        ctx.sqlTemplatesGeoJson = rule == null ? List.of() : rule.sqlTemplatesGeoJson();
        ctx.sqlTemplatesNoFilter = rule == null ? List.of() : rule.sqlTemplatesNoFilter();
        return ctx;
    }

    private static final class CurrentStepContext {
        String stepId = "";
        String stepTitle = "";
        String jdbcUrl = "";
        List<String> allowedTables = List.of();
        List<String> sqlTemplates = List.of();
        List<String> sqlTemplatesGeoJson = List.of();
        List<String> sqlTemplatesNoFilter = List.of();

        boolean hasTemplates() {
            return !sqlTemplates.isEmpty() || !sqlTemplatesGeoJson.isEmpty() || !sqlTemplatesNoFilter.isEmpty();
        }
    }

    private ToolResult executeQuery(Connection connection, String sql, int maxRows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(maxRows);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                return tableResult("Query returned", sql, resultSet, maxRows);
            } catch (SQLException error) {
                ToolResult recovered = tryAutoRepair(connection, sql, error, maxRows);
                if (recovered != null) {
                    return recovered;
                }
                return buildSqlFailure(connection, sql, error, maxRows);
            }
        }
    }

    private ToolResult rejectTableSchemaProbe(String sql, String normalizedSql, JsonNode args, String targetSchema) throws Exception {
        if (sql == null || normalizedSql == null) {
            return null;
        }
        String reason = "";
        String table = "";
        Matcher describe = DESCRIBE_PATTERN.matcher(sql);
        if (describe.matches()) {
            reason = "DESCRIBE is a schema probe";
            table = describe.group(1);
        }
        Matcher showColumns = SHOW_COLUMNS_PATTERN.matcher(sql);
        if (reason.isBlank() && showColumns.matches()) {
            reason = "SHOW COLUMNS is a schema probe";
            table = showColumns.group(1);
        }
        if (reason.isBlank() && normalizedSql.contains("information_schema.columns")) {
            reason = "information_schema.columns is a schema probe";
            table = extractTableNameFilter(sql);
        }
        if (reason.isBlank() && normalizedSql.contains("pg_catalog.pg_attribute")) {
            reason = "pg_catalog.pg_attribute is a schema probe";
        }
        if (reason.isBlank() && looksLikeSelectStarLimitOne(normalizedSql)) {
            reason = "SELECT * LIMIT 1 is a schema/sample probe";
            table = extractFirstTable(sql);
        }
        if (reason.isBlank()) {
            return null;
        }
        table = cleanName(table);
        Map<String, Object> suggestedArgs = new LinkedHashMap<>();
        if (!table.isBlank()) {
            suggestedArgs.put("table", table);
        }
        String schema = blankToNull(args.path("schema").asText(""));
        if (schema == null) {
            schema = blankToNull(targetSchema);
        }
        if (schema != null && !schema.isBlank() && !table.contains(".")) {
            suggestedArgs.put("schema", schema);
        }
        copyIfPresent(args, suggestedArgs, "jdbcUrl");
        copyIfPresent(args, suggestedArgs, "dbType");
        copyIfPresent(args, suggestedArgs, "host");
        copyIfPresent(args, suggestedArgs, "port");
        copyIfPresent(args, suggestedArgs, "database");
        copyIfPresent(args, suggestedArgs, "username");
        copyIfPresent(args, suggestedArgs, "password");
        return new ToolResult(
                false,
                "schema probe rejected; use db.schema.inspect",
                objectMapper.writeValueAsString(linkedMapOf(
                        "displayType", "tool_error",
                        "recoverable", true,
                        "retryRecommended", true,
                        "reason", reason,
                        "recommendedTool", "db.schema.inspect",
                        "suggestedArguments", suggestedArgs,
                        "message", "Do not inspect table schema through db.query. Call db.schema.inspect for the target table."
                )),
                "[]",
                "schema inspection must use db.schema.inspect"
        );
    }

    private boolean looksLikeSelectStarLimitOne(String normalizedSql) {
        return normalizedSql.matches("(?is)^\\s*select\\s+\\*\\s+from\\s+.+\\blimit\\s+1\\s*;?\\s*$");
    }

    private String extractFirstTable(String sql) {
        Matcher matcher = FROM_OR_JOIN_PATTERN.matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractTableNameFilter(String sql) {
        Matcher matcher = Pattern.compile("(?is)\\btable_name\\s*=\\s*'([^']+)'").matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String cleanName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace(";", "")
                .replace("\"", "")
                .replace("`", "");
    }

    private void copyIfPresent(JsonNode args, Map<String, Object> target, String field) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            String text = value.asText("");
            if (!text.isBlank()) {
                target.put(field, text);
            }
            return;
        }
        if (value.isNumber()) {
            target.put(field, value.numberValue());
        }
    }

    private ToolResult tryMetadataShortcut(Connection connection, String sql, String normalized, String defaultSchema, int maxRows) throws Exception {
        Matcher describe = DESCRIBE_PATTERN.matcher(sql);
        if (describe.matches()) {
            QualifiedName name = parseQualifiedName(describe.group(1), defaultSchema);
            return describeTable(connection, name, sql, maxRows, "DESCRIBE");
        }

        Matcher showColumns = SHOW_COLUMNS_PATTERN.matcher(sql);
        if (showColumns.matches()) {
            String schemaOverride = blankToNull(cleanIdentifier(showColumns.group(2)));
            QualifiedName name = parseQualifiedName(showColumns.group(1), schemaOverride != null ? schemaOverride : defaultSchema);
            return describeTable(connection, name, sql, maxRows, "SHOW COLUMNS");
        }

        Matcher showTables = SHOW_TABLES_PATTERN.matcher(sql);
        if (showTables.matches() || normalized.equals("show tables")) {
            String schema = showTables.matches() ? blankToNull(cleanIdentifier(showTables.group(1))) : blankToNull(defaultSchema);
            return listTables(connection, schema, sql, maxRows);
        }

        return null;
    }

    private ToolResult describeTable(Connection connection, QualifiedName table, String originalSql, int maxRows, String mode) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        // Schema inspection should be complete and stable: do not truncate by request maxRows.
        final int schemaHardLimit = 2048;
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), table.schema(), table.table(), "%")) {
            while (columns.next() && rows.size() < schemaHardLimit) {
                int jdbcTypeCode = columns.getInt("DATA_TYPE");
                String vendorTypeName = columns.getString("TYPE_NAME");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("column_name", columns.getString("COLUMN_NAME"));
                // data_type: normalized JDBC/SQL type label (portable)
                row.put("data_type", normalizeJdbcTypeName(jdbcTypeCode, vendorTypeName));
                // type_name: vendor-specific database type name (original)
                row.put("type_name", blankToNull(vendorTypeName));
                row.put("jdbc_type", jdbcTypeCode);
                row.put("column_size", columns.getInt("COLUMN_SIZE"));
                row.put("nullable", nullableLabel(columns.getInt("NULLABLE")));
                row.put("default_value", columns.getString("COLUMN_DEF"));
                row.put("ordinal_position", columns.getInt("ORDINAL_POSITION"));
                row.put("remarks", blankToNull(columns.getString("REMARKS")));
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return buildRecoverableFailure(
                    connection,
                    originalSql,
                    table.schema(),
                    "table metadata not found",
                    "table_not_found",
                    null,
                    true,
                    List.of(
                            "The requested table was not found via JDBC metadata.",
                            "Check schema/table spelling and retry.",
                            "You can inspect available tables first with SHOW TABLES."
                    ),
                    findSimilarTables(connection, table.schema(), table.table(), Math.min(maxRows, 20))
            );
        }
        return metadataTableResult(
                "%s returned %d columns".formatted(mode, rows.size()),
                originalSql,
                List.of("column_name", "data_type", "type_name", "jdbc_type", "column_size", "nullable", "default_value", "ordinal_position", "remarks"),
                rows,
                linkedMapOf(
                        "inspectionMode", mode,
                        "schema", table.schema(),
                        "table", table.table(),
                        "complete", true
                )
        );
    }

    private String normalizeJdbcTypeName(int jdbcTypeCode, String vendorTypeName) {
        try {
            return JDBCType.valueOf(jdbcTypeCode).getName();
        } catch (Exception ignored) {
            return blankToNull(vendorTypeName);
        }
    }

    private ToolResult listTables(Connection connection, String schema, String originalSql, int maxRows) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && rows.size() < maxRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_schema", blankToNull(tables.getString("TABLE_SCHEM")));
                row.put("table_name", tables.getString("TABLE_NAME"));
                row.put("table_type", tables.getString("TABLE_TYPE"));
                row.put("remarks", blankToNull(tables.getString("REMARKS")));
                rows.add(row);
            }
        }
        return metadataTableResult(
                "SHOW TABLES returned %d objects".formatted(rows.size()),
                originalSql,
                List.of("table_schema", "table_name", "table_type", "remarks"),
                rows,
                linkedMapOf("inspectionMode", "SHOW TABLES", "schema", schema)
        );
    }

    private ToolResult tableResult(String summaryPrefix, String sql, ResultSet resultSet, int maxRows) throws Exception {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(metaData.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : columns) {
                row.put(column, resultSet.getObject(column));
            }
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("displayType", "table");
        payload.put("columns", columns);
        payload.put("rows", rows);
        payload.put("sql", sql);
        return new ToolResult(
                true,
                summaryPrefix + " " + rows.size() + " rows",
                objectMapper.writeValueAsString(payload),
                "[]",
                null
        );
    }

    private ToolResult metadataTableResult(String summary, String originalSql, List<String> columns, List<Map<String, Object>> rows, Map<String, Object> metadata) throws Exception {
        return new ToolResult(
                true,
                summary,
                objectMapper.writeValueAsString(linkedMapOf(
                        "displayType", "table",
                        "columns", columns,
                        "rows", rows,
                        "sql", originalSql,
                        "metadata", metadata
                )),
                "[]",
                null
        );
    }

    private ToolResult buildSqlFailure(Connection connection, String sql, SQLException error, int maxRows) throws Exception {
        String errorType = classify(error);
        List<String> repairHints = new ArrayList<>();
        repairHints.add("Do not repeat the same SQL unchanged.");
        repairHints.add("If the intent is table schema inspection, call db.schema.inspect with the target table and same connection arguments.");

        List<Map<String, Object>> recoveryCandidates = new ArrayList<>();
        if ("undefined_table".equals(errorType) || "invalid_schema".equals(errorType)) {
            String tableName = firstReferencedTable(sql);
            recoveryCandidates.addAll(findSimilarTables(connection, safeSchema(connection), tableName, Math.min(maxRows, 20)));
            repairHints.add("Verify schema and table names against the candidate tables returned in Data JSON.");
        } else if ("undefined_column".equals(errorType)) {
            String tableName = firstReferencedTable(sql);
            if (tableName != null) {
                QualifiedName table = parseQualifiedName(tableName, safeSchema(connection));
                recoveryCandidates.addAll(describeTableRows(connection, table, Math.min(maxRows, 30)));
                repairHints.add("Inspect returned columns and replace the missing column name before retrying.");
            }
            if (sql.toLowerCase(Locale.ROOT).contains("information_schema.columns")) {
                repairHints.add("Do not query information_schema.columns through db.query; use db.schema.inspect for column metadata.");
            }
        } else if ("syntax_error".equals(errorType)) {
            repairHints.add("Use ANSI SELECT syntax for business queries. Use db.schema.inspect for table schema.");
        }

        return buildRecoverableFailure(
                connection,
                sql,
                safeSchema(connection),
                "query failed",
                errorType,
                error,
                true,
                repairHints,
                recoveryCandidates
        );
    }

    private ToolResult tryAutoRepair(Connection connection, String sql, SQLException error, int maxRows) throws Exception {
        String product = safeDatabaseProduct(connection).toLowerCase(Locale.ROOT);
        String normalizedSql = sql.toLowerCase(Locale.ROOT);
        if ("undefined_column".equals(classify(error))) {
            String repairedSql = repairUndefinedColumnSql(connection, sql, error);
            if (!repairedSql.equals(sql)) {
                log.info("db.query.auto_repair applied=undefined_column sql={}", repairedSql);
                ToolResult repaired = executeRepairedQuery(connection, sql, repairedSql, "column-name auto repair", maxRows);
                if (repaired != null) {
                    return repaired;
                }
            }
        }

        if (product.contains("postgresql")
                && "undefined_column".equals(classify(error))
                && normalizedSql.contains("from information_schema.columns")
                && normalizedSql.contains("column_comment")) {
            String repairedSql = repairInformationSchemaCommentQuery(sql);
            if (!repairedSql.equals(sql)) {
                log.info("db.query.auto_repair applied=postgres_column_comment sql={}", repairedSql);
                try (Statement retry = connection.createStatement()) {
                    retry.setMaxRows(maxRows);
                    try (ResultSet repairedResultSet = retry.executeQuery(repairedSql)) {
                        ToolResult result = tableResult("Query returned after auto-repair", repairedSql, repairedResultSet, maxRows);
                        Map<String, Object> payload = new LinkedHashMap<>(objectMapper.readValue(result.dataJson(), Map.class));
                        payload.put("originalSql", sql);
                        payload.put("autoRepaired", true);
                        payload.put("repairReason", "PostgreSQL information_schema.columns does not expose column_comment; backend rewrote the query.");
                        return new ToolResult(
                                true,
                                result.summary(),
                                objectMapper.writeValueAsString(payload),
                                result.artifactsJson(),
                                null
                        );
                    }
                } catch (SQLException retryError) {
                    log.warn("db.query.auto_repair failed sql={}, error={}", repairedSql, retryError.getMessage());
                }
            }
        }
        return null;
    }

    private ToolResult executeRepairedQuery(Connection connection, String originalSql, String repairedSql, String reason, int maxRows) throws Exception {
        try (Statement retry = connection.createStatement()) {
            retry.setMaxRows(maxRows);
            try (ResultSet repairedResultSet = retry.executeQuery(repairedSql)) {
                ToolResult result = tableResult("Query returned after auto-repair", repairedSql, repairedResultSet, maxRows);
                Map<String, Object> payload = new LinkedHashMap<>(objectMapper.readValue(result.dataJson(), Map.class));
                payload.put("originalSql", originalSql);
                payload.put("autoRepaired", true);
                payload.put("repairReason", reason);
                return new ToolResult(
                        true,
                        result.summary(),
                        objectMapper.writeValueAsString(payload),
                        result.artifactsJson(),
                        null
                );
            }
        } catch (SQLException retryError) {
            log.warn("db.query.auto_repair failed sql={}, error={}", repairedSql, retryError.getMessage());
            return null;
        }
    }

    private String repairUndefinedColumnSql(Connection connection, String sql, SQLException error) {
        String repaired = sql;
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        String missing = extractMissingColumn(error);

        if (lowerSql.contains("from information_schema.columns")) {
            repaired = repaired.replaceAll("(?i)\\btype_name\\b", "data_type");
            repaired = repaired.replaceAll("(?i)\\bis_nulllable\\b", "is_nullable");
        }

        String tableName = firstReferencedTable(sql);
        if (tableName != null) {
            QualifiedName table = parseQualifiedName(tableName, safeSchema(connection));
            Set<String> knownColumns = listKnownColumns(connection, table);
            repaired = repairByKnownColumns(repaired, knownColumns, missing);
        }

        String suggested = extractSuggestedColumn(error);
        if (missing != null && suggested != null) {
            repaired = replaceWholeWordIgnoreCase(repaired, missing, suggested);
        }
        return repaired;
    }

    private String extractMissingColumn(SQLException error) {
        if (error == null || error.getMessage() == null) {
            return null;
        }
        Matcher matcher = UNDEFINED_COLUMN_PATTERN.matcher(error.getMessage());
        if (!matcher.find()) {
            return null;
        }
        return cleanIdentifier(matcher.group(1));
    }

    private String extractSuggestedColumn(SQLException error) {
        if (error == null || error.getMessage() == null) {
            return null;
        }
        Matcher matcher = PG_SUGGEST_COLUMN_PATTERN.matcher(error.getMessage());
        if (!matcher.find()) {
            return null;
        }
        String raw = cleanIdentifier(matcher.group(1));
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private String replaceWholeWordIgnoreCase(String sql, String fromToken, String toToken) {
        if (sql == null || fromToken == null || fromToken.isBlank() || toToken == null || toToken.isBlank()) {
            return sql;
        }
        String escaped = Pattern.quote(fromToken);
        return sql.replaceAll("(?i)\\b" + escaped + "\\b", Matcher.quoteReplacement(toToken));
    }

    private String normalizeSqlBeforeExecution(String sql, String defaultSchema) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String repaired = sql;
        String lower = repaired.toLowerCase(Locale.ROOT);

        if (lower.contains("information_schema.columns")) {
            repaired = repaired.replaceAll("(?i)\\bxmap\\.information_schema\\.columns\\b", "information_schema.columns");
            repaired = repaired.replaceAll("(?i)\\btype_name\\b", "data_type");
            repaired = repaired.replaceAll("(?i)\\bis_nulllable\\b", "is_nullable");
            repaired = ensureTableSchemaFilter(repaired, defaultSchema);
        }
        return repaired;
    }

    private String ensureTableSchemaFilter(String sql, String defaultSchema) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        if (defaultSchema == null || defaultSchema.isBlank()) {
            return sql;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains("information_schema.columns")) {
            return sql;
        }
        if (lower.contains("table_schema")) {
            return sql;
        }
        Matcher whereMatcher = Pattern.compile("(?is)\\bwhere\\b").matcher(sql);
        if (!whereMatcher.find()) {
            return sql;
        }
        int insertAt = sql.length();
        Matcher tailMatcher = Pattern.compile("(?is)\\b(order\\s+by|group\\s+by|limit|offset)\\b").matcher(sql);
        if (tailMatcher.find(whereMatcher.end())) {
            insertAt = tailMatcher.start();
        }
        String predicate = " table_schema = '" + defaultSchema + "' AND";
        return sql.substring(0, whereMatcher.end()) + predicate + sql.substring(whereMatcher.end(), insertAt) + sql.substring(insertAt);
    }

    private String repairByKnownColumns(String sql, Set<String> knownColumns, String missingColumn) {
        if (sql == null || sql.isBlank() || knownColumns == null || knownColumns.isEmpty()) {
            return sql;
        }
        if (missingColumn == null || missingColumn.isBlank()) {
            return sql;
        }
        String repaired = sql;

        String missing = missingColumn.toLowerCase(Locale.ROOT);
        if (Set.of("used_freq", "usage_freq", "usage_frequency").contains(missing)) {
            String frequencyColumn = resolveFrequencyColumn(knownColumns);
            if (frequencyColumn != null && !frequencyColumn.equalsIgnoreCase(missingColumn)) {
                repaired = replaceWholeWordIgnoreCase(repaired, missingColumn, frequencyColumn);
            }
            return repaired;
        }

        if (Set.of("long", "lng", "lon", "longitude").contains(missing) && knownColumns.contains("longitude")) {
            if (!"longitude".equals(missing)) {
                repaired = replaceWholeWordIgnoreCase(repaired, missingColumn, "longitude");
            }
            return repaired;
        }

        if (Set.of("lat", "latitude").contains(missing) && knownColumns.contains("latitude")) {
            if (!"latitude".equals(missing)) {
                repaired = replaceWholeWordIgnoreCase(repaired, missingColumn, "latitude");
            }
        }
        return repaired;
    }

    private String resolveFrequencyColumn(Set<String> knownColumns) {
        if (knownColumns.contains("used_freq")) {
            return "used_freq";
        }
        if (knownColumns.contains("fre_band")) {
            return "fre_band";
        }
        if (knownColumns.contains("freq_band")) {
            return "freq_band";
        }
        if (knownColumns.contains("frequency")) {
            return "frequency";
        }
        for (String column : knownColumns) {
            if (column == null) {
                continue;
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (normalized.contains("freq") || normalized.contains("band")) {
                return column;
            }
        }
        return null;
    }

    private Set<String> listKnownColumns(Connection connection, QualifiedName table) {
        Set<String> columns = new LinkedHashSet<>();
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(connection.getCatalog(), table.schema(), table.table(), "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    if (columnName != null && !columnName.isBlank()) {
                        columns.add(columnName.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("db.query.auto_repair.column_cache_failed table={}.{}, error={}",
                    table.schema(), table.table(), ex.getMessage());
        }
        return columns;
    }

    private ToolResult buildRecoverableFailure(
            Connection connection,
            String sql,
            String schema,
            String summary,
            String errorType,
            SQLException error,
            boolean recoverable,
            List<String> repairHints,
            List<Map<String, Object>> recoveryCandidates
    ) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "tool_error");
        data.put("tool", name());
        data.put("sql", sql);
        data.put("recoverable", recoverable);
        data.put("retryRecommended", recoverable);
        data.put("errorType", errorType);
        data.put("databaseProduct", safeDatabaseProduct(connection));
        data.put("schema", blankToNull(schema));
        data.put("repairHints", repairHints);
        data.put("recoveryCandidates", recoveryCandidates);
        if (error != null) {
            data.put("message", error.getMessage());
            data.put("sqlState", blankToNull(error.getSQLState()));
            data.put("vendorCode", error.getErrorCode());
        }
        return new ToolResult(
                false,
                recoverable ? "%s, retry with corrected SQL".formatted(summary) : summary,
                objectMapper.writeValueAsString(data),
                "[]",
                error != null ? error.getMessage() : summary
        );
    }

    private List<Map<String, Object>> describeTableRows(Connection connection, QualifiedName table, int maxRows) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), table.schema(), table.table(), "%")) {
            while (columns.next() && rows.size() < maxRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("column_name", columns.getString("COLUMN_NAME"));
                row.put("type_name", columns.getString("TYPE_NAME"));
                row.put("data_type", columns.getString("TYPE_NAME"));
                row.put("nullable", nullableLabel(columns.getInt("NULLABLE")));
                row.put("ordinal_position", columns.getInt("ORDINAL_POSITION"));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> findSimilarTables(Connection connection, String schema, String requestedTable, int limit) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        String likePattern = requestedTable == null || requestedTable.isBlank() ? "%" : "%" + cleanIdentifier(requestedTable) + "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), schema, likePattern, new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && rows.size() < limit) {
                String tableSchema = blankToNull(tables.getString("TABLE_SCHEM"));
                String tableName = tables.getString("TABLE_NAME");
                String dedupKey = tableSchema + "." + tableName;
                if (!dedup.add(dedupKey)) {
                    continue;
                }
                rows.add(new LinkedHashMap<>(Map.of(
                        "table_schema", tableSchema == null ? "" : tableSchema,
                        "table_name", tableName,
                        "table_type", tables.getString("TABLE_TYPE")
                )));
            }
        }
        if (!rows.isEmpty() || requestedTable == null || requestedTable.isBlank()) {
            return rows;
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && rows.size() < limit) {
                String tableSchema = blankToNull(tables.getString("TABLE_SCHEM"));
                String tableName = tables.getString("TABLE_NAME");
                String dedupKey = tableSchema + "." + tableName;
                if (!dedup.add(dedupKey)) {
                    continue;
                }
                rows.add(new LinkedHashMap<>(Map.of(
                        "table_schema", tableSchema == null ? "" : tableSchema,
                        "table_name", tableName,
                        "table_type", tables.getString("TABLE_TYPE")
                )));
            }
        }
        return rows;
    }

    private String firstReferencedTable(String sql) {
        Matcher matcher = FROM_OR_JOIN_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
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

    private String classify(SQLException error) {
        String sqlState = safe(error.getSQLState()).toUpperCase(Locale.ROOT);
        String message = safe(error.getMessage()).toLowerCase(Locale.ROOT);
        if (sqlState.equals("42P01") || sqlState.equals("42S02") || message.contains("does not exist") || message.contains("unknown table") || message.contains("invalid object name")) {
            return "undefined_table";
        }
        if (sqlState.equals("42703") || sqlState.equals("42S22") || message.contains("unknown column") || message.contains("column") && message.contains("does not exist") || message.contains("invalid column name")) {
            return "undefined_column";
        }
        if (sqlState.equals("42601") || message.contains("syntax error") || message.contains("you have an error in your sql syntax")) {
            return "syntax_error";
        }
        if (sqlState.equals("3F000") || message.contains("schema") && message.contains("does not exist")) {
            return "invalid_schema";
        }
        return "query_error";
    }

    private String repairInformationSchemaCommentQuery(String sql) {
        String repaired = sql.replaceAll("(?i)\\bcolumn_comment\\b", "null::text as column_comment");
        return repaired;
    }

    private String nullableLabel(int nullable) {
        return switch (nullable) {
            case DatabaseMetaData.columnNoNulls -> "NO";
            case DatabaseMetaData.columnNullable -> "YES";
            default -> "UNKNOWN";
        };
    }

    private String safeDatabaseProduct(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> linkedMapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put((String) entries[index], entries[index + 1]);
        }
        return map;
    }

    private record QualifiedName(String schema, String table) {
    }

}
