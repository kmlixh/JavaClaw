package com.janyee.agent.runtime.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.AgentEventType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class DefaultToolResultAppender implements ToolResultAppender {

    private static final Pattern DESCRIBE_PATTERN = Pattern.compile("(?is)^\\s*(?:describe|desc)\\s+([\\w.\\\"]+)");
    private static final Pattern SHOW_COLUMNS_PATTERN = Pattern.compile("(?is)^\\s*show\\s+columns\\s+(?:from|in)\\s+([\\w.\\\"]+)");
    private static final Pattern SQL_ALIAS_PATTERN = Pattern.compile("(?i)\\s+as\\s+[\\w\"`\\[\\]]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    /** Strip the trailing "order by ..." clause — its column list commonly references aliases that vary between semantically equivalent SQLs. */
    private static final Pattern SQL_ORDER_BY_PATTERN = Pattern.compile(
            "(?is)\\s+order\\s+by\\s+.+?(?=(\\slimit\\s|\\soffset\\s|$))"
    );
    private static final Pattern SQL_SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,)])");
    private static final java.util.Set<String> NON_SUMMARY_TOOLS = java.util.Set.of(
            "plan.create", "plan.update"
    );

    private final ObjectMapper objectMapper;

    public DefaultToolResultAppender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(ToolLoopContext context, ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            context.setLastModelRawOutput("""
                    Tool execution failed.
                    Tool: %s
                    Arguments: %s
                    Error: %s

                    If the failure is recoverable, explain it briefly and ask for a narrower request.
                    Do not repeat the same tool call with identical arguments.
                    """.formatted(
                    outcome.request().toolName(),
                    safe(outcome.request().argumentsJson()),
                    safe(outcome.errorMessage())
            ));
            return;
        }

        if (!outcome.success()) {
            context.setLastModelRawOutput("""
                    Tool execution result:
                    Tool: %s
                    Arguments: %s
                    Success: false
                    Summary: %s
                    Error: %s
                    Data JSON:
                    %s

                    The tool call failed, but it may still be recoverable.
                    If Data JSON marks the failure as recoverable or retryRecommended, correct the arguments and retry once with different arguments.
                    For table schema inspection, prefer db.schema.inspect with explicit jdbcUrl/schema/table instead of writing DESCRIBE or information_schema SQL manually.
                    Reuse stored schema context only for tables that are already listed there.
                    If the failure references a different table or an unknown column not covered by stored schema context, inspect that exact table with db.schema.inspect.
                    Do not repeat the same tool call with identical arguments.
                    """.formatted(
                    outcome.request().toolName(),
                    safe(outcome.request().argumentsJson()),
                    safe(outcome.toolResult().summary()),
                    safe(outcome.toolResult().error()),
                    truncate(safe(outcome.toolResult().dataJson()), 4000)
            ));
            return;
        }

        recordCompletedSummary(context, outcome);
        emitPlanSnapshotIfRelevant(context, outcome);

        if (isSchemaInspectionOutcome(outcome)) {
            String schemaKey = schemaContextKey(outcome);
            String schemaSnapshot = buildSchemaSnapshot(outcome);
            context.addSchemaContext(schemaKey, schemaSnapshot);
            context.setLastModelRawOutput("""
                    Schema inspection completed successfully by %s.
                    Schema key: %s
                    The schema context has been stored and accumulated with prior table schemas.
                    Use the stored schema context directly for this table and proceed to business SQL.
                    Do not run DESCRIBE / SHOW COLUMNS / information_schema.columns again for the same table in this run.
                    For another table, use db.schema.inspect with that exact table instead of writing metadata SQL manually.
                    For PostgreSQL information_schema.columns, valid column names include column_name, data_type, is_nullable.
                    """.formatted(outcome.request().toolName(), schemaKey));
            return;
        }

        context.setLastModelRawOutput("""
                Tool execution result:
                Tool: %s
                Arguments: %s
                Success: %s
                Summary: %s
                Data JSON:
                %s
                Artifacts JSON:
                %s

                Use the tool result above to answer the user's latest request directly.
                If the tool output already contains the needed information, provide the final answer instead of calling the same tool again.
                When listing files, include concrete file or directory names from Data JSON.
                Do not repeat raw rows, JSON payload, or large tables in the final chat answer — summarize briefly and put the detailed data into the artifact.
                When the user asks for grouping, aggregation, statistics, trends, or visualization, prefer:
                1) db.query for the aggregate SQL
                2) a brief natural-language summary in chat
                For tables in the final report: write a GitHub-flavored markdown table by hand inside the artifact.markdown body. There is no table-rendering tool.
                For charts in the final report: write a ```echarts\n{ECharts option JSON}\n``` fenced code block inside the artifact.markdown body. The frontend MarkdownBlock component renders the fenced block into an interactive chart. There is no chart-rendering tool.
                """.formatted(
                outcome.request().toolName(),
                safe(outcome.request().argumentsJson()),
                outcome.success(),
                safe(outcome.toolResult().summary()),
                truncate(safe(outcome.toolResult().dataJson()), 4000),
                truncate(safe(outcome.toolResult().artifactsJson()), 2000)
        ));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String safeNullable(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }

    private boolean isSchemaInspectionOutcome(ToolCallOutcome outcome) {
        String toolName = outcome.request().toolName();
        String dataJson = safeNullable(outcome.toolResult().dataJson());
        if ("db.schema.inspect".equals(toolName)) {
            return dataJson.contains("\"rows\"");
        }
        return "db.query".equals(toolName) && dataJson.contains("\"inspectionMode\"");
    }

    private String schemaContextKey(ToolCallOutcome outcome) {
        String requestKey = schemaContextKeyFromRequest(outcome);
        if (!requestKey.isBlank()) {
            return requestKey;
        }
        String dataJson = safeNullable(outcome.toolResult().dataJson());
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            JsonNode metadata = root.path("metadata");
            String dataSource = metadata.path("dataSource").path("fingerprint").asText("");
            String qualifiedName = metadata.path("qualifiedName").asText("");
            if (qualifiedName.isBlank()) {
                String schema = metadata.path("schema").asText("");
                String table = metadata.path("table").asText("");
                qualifiedName = schema.isBlank() ? table : schema + "." + table;
            }
            String inspectionMode = metadata.path("inspectionMode").asText("");
            if (!qualifiedName.isBlank()) {
                return normalizeKey(dataSource + "|" + qualifiedName);
            }
            if (!inspectionMode.isBlank()) {
                return normalizeKey(dataSource + "|" + inspectionMode + "|" + metadata.path("schema").asText(""));
            }
        } catch (Exception ignored) {
            // fall through to request-based key
        }
        return normalizeKey(outcome.request().toolName() + "|" + safeNullable(outcome.request().argumentsJson()));
    }

    private String schemaContextKeyFromRequest(ToolCallOutcome outcome) {
        if (outcome == null || outcome.request() == null) {
            return "";
        }
        String toolName = safeNullable(outcome.request().toolName());
        String argsJson = safeNullable(outcome.request().argumentsJson());
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            String jdbcUrl = args.path("jdbcUrl").asText("");
            if ("db.schema.inspect".equals(toolName)) {
                String table = args.path("table").asText("");
                if (table.isBlank()) {
                    return "";
                }
                String schema = args.path("schema").asText("");
                String qualified = table.contains(".") || schema.isBlank() ? table : schema + "." + table;
                return normalizeKey(jdbcUrl + "|" + qualified);
            }
            if ("db.query".equals(toolName)) {
                String sql = args.path("sql").asText("");
                String table = firstMatch(DESCRIBE_PATTERN, sql);
                if (table.isBlank()) {
                    table = firstMatch(SHOW_COLUMNS_PATTERN, sql);
                }
                if (!table.isBlank()) {
                    return normalizeKey(jdbcUrl + "|" + table.replace("\"", ""));
                }
            }
        } catch (Exception ignored) {
            // fall through to metadata-based key
        }
        return "";
    }

    private String buildSchemaSnapshot(ToolCallOutcome outcome) {
        String dataJson = safeNullable(outcome.toolResult().dataJson());
        List<ColumnEntry> entries = extractColumnEntries(dataJson);

        String qualifiedName = resolveQualifiedName(outcome, dataJson);
        List<String> primaryKeys = new ArrayList<>();
        List<String> spatialColumns = new ArrayList<>();
        List<String> freqCandidates = new ArrayList<>();
        List<String> lonCandidates = new ArrayList<>();
        List<String> latCandidates = new ArrayList<>();
        StringBuilder columnsInline = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            ColumnEntry entry = entries.get(i);
            if (i > 0) {
                columnsInline.append(", ");
            }
            if (entry.type() == null || entry.type().isBlank()) {
                columnsInline.append(entry.name());
            } else {
                columnsInline.append(entry.name()).append(':').append(entry.type());
            }
            if (entry.primaryKey()) {
                primaryKeys.add(entry.name());
            }
            String lower = entry.name().toLowerCase(Locale.ROOT);
            String type = entry.type() == null ? "" : entry.type().toLowerCase(Locale.ROOT);
            if (lower.contains("geom") || type.equals("other") || type.equals("geometry")) {
                spatialColumns.add(entry.name());
            }
            if (lower.contains("freq")) {
                freqCandidates.add(entry.name());
            }
            if (lower.contains("lon") || lower.contains("lng")) {
                lonCandidates.add(entry.name());
            }
            if (lower.contains("lat")) {
                latCandidates.add(entry.name());
            }
        }

        StringBuilder snapshot = new StringBuilder();
        snapshot.append("Table: ").append(qualifiedName.isBlank() ? schemaContextKey(outcome) : qualifiedName)
                .append(" (").append(entries.size()).append(" cols)\n");
        snapshot.append("  cols: ").append(entries.isEmpty() ? "(none)" : columnsInline).append('\n');
        if (!primaryKeys.isEmpty()) {
            snapshot.append("  pk: ").append(String.join(",", primaryKeys)).append('\n');
        }
        String hint = buildHintLine(freqCandidates, lonCandidates, latCandidates, spatialColumns);
        if (!hint.isEmpty()) {
            snapshot.append("  hint: ").append(hint);
        }
        return snapshot.toString().stripTrailing();
    }

    private String buildHintLine(List<String> freq, List<String> lon, List<String> lat, List<String> spatial) {
        List<String> parts = new ArrayList<>();
        if (!freq.isEmpty()) {
            parts.add("freq=" + String.join(",", freq));
        }
        if (!lon.isEmpty()) {
            parts.add("lon=" + String.join(",", lon));
        }
        if (!lat.isEmpty()) {
            parts.add("lat=" + String.join(",", lat));
        }
        if (!spatial.isEmpty()) {
            parts.add("spatial=" + String.join(",", spatial));
        }
        return String.join(" | ", parts);
    }

    private String resolveQualifiedName(ToolCallOutcome outcome, String dataJson) {
        try {
            JsonNode metadata = objectMapper.readTree(dataJson).path("metadata");
            String qualified = metadata.path("qualifiedName").asText("");
            if (!qualified.isBlank()) {
                return qualified;
            }
            String schema = metadata.path("schema").asText("");
            String table = metadata.path("table").asText("");
            if (!table.isBlank()) {
                return schema.isBlank() ? table : schema + "." + table;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private String normalizeKey(String value) {
        return safeNullable(value).trim().toLowerCase(Locale.ROOT);
    }

    private String firstMatch(Pattern pattern, String value) {
        java.util.regex.Matcher matcher = pattern.matcher(safeNullable(value));
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<ColumnEntry> extractColumnEntries(String dataJson) {
        List<ColumnEntry> columns = new ArrayList<>();
        if (dataJson == null || dataJson.isBlank()) {
            return columns;
        }
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            JsonNode rows = root.path("rows");
            if (!rows.isArray()) {
                return columns;
            }
            for (JsonNode row : rows) {
                String columnName = row.path("column_name").asText("");
                if (columnName.isBlank()) {
                    continue;
                }
                boolean alreadySeen = false;
                for (ColumnEntry existing : columns) {
                    if (existing.name().equals(columnName)) {
                        alreadySeen = true;
                        break;
                    }
                }
                if (alreadySeen) {
                    continue;
                }
                columns.add(new ColumnEntry(
                        columnName,
                        row.path("data_type").asText(""),
                        row.path("is_primary_key").asBoolean(false)
                ));
            }
        } catch (Exception ignored) {
            // keep empty list when parsing fails
        }
        return columns;
    }

    private record ColumnEntry(String name, String type, boolean primaryKey) {}

    private void emitPlanSnapshotIfRelevant(ToolLoopContext context, ToolCallOutcome outcome) {
        if (outcome == null || outcome.request() == null) {
            return;
        }
        String toolName = outcome.request().toolName();
        if (toolName == null || !toolName.startsWith("plan.")) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(context.runPlan().toSnapshot());
            context.emitEvent(AgentEventType.PLAN_UPDATED, payload);
        } catch (Exception ignored) {
            // Plan UI is best-effort; never fail the run because the snapshot could not be serialized.
        }
    }

    private void recordCompletedSummary(ToolLoopContext context, ToolCallOutcome outcome) {
        if (outcome == null || outcome.request() == null) {
            return;
        }
        String toolName = outcome.request().toolName();
        if (toolName == null || NON_SUMMARY_TOOLS.contains(toolName)) {
            return;
        }
        String fingerprint = argumentsFingerprint(toolName, outcome.request().argumentsJson());
        int rowCount = extractRowCount(outcome);
        String summaryText = outcome.toolResult() != null ? outcome.toolResult().summary() : null;
        // For db.query aggregate results the bare "Query returned N rows" summary hides the
        // actual values (COUNT(*), AVG, SUM) from later iterations. Inline the first 1–2 rows
        // so the LLM can read real numbers out of [Completed Queries] when writing the final
        // artifact, instead of falling back to "见实际查询" placeholders.
        if ("db.query".equals(toolName) && outcome.success()) {
            String preview = extractResultPreview(outcome);
            if (!preview.isBlank()) {
                summaryText = safe(summaryText) + " → " + preview;
            }
        }

        // 观察到 LLM 经常跳过 plan.update IN_PROGRESS 直接调 artifact.markdown —— 导致
        // currentInProgressStep()=empty，summary 挂不上 step，run-end gate 最终被 report=PENDING
        // 卡死。这里兜底一下：artifact.* 成功时，如果没有 IN_PROGRESS step 但有一个 toolHint
        // 匹配此工具、且还没 COMPLETED 的 step（通常就是 report），把它强制推进到 COMPLETED
        // 并把 summary 挂上去。真实产物已经落盘，LLM 的 plan.update 礼仪错误不应该阻挠结束。
        final PlanStep autoAdvanced;
        if (outcome.success() && toolName.startsWith("artifact.")
                && context.runPlan().currentInProgressStep().isEmpty()) {
            autoAdvanced = findMatchingArtifactStep(context, toolName).orElse(null);
        } else {
            autoAdvanced = null;
        }

        final PlanStep autoAdvancedFinal = autoAdvanced;
        String stepId = context.runPlan().currentInProgressStep().map(PlanStep::id)
                .orElseGet(() -> autoAdvancedFinal != null ? autoAdvancedFinal.id() : "");
        CompletedToolSummary completed = CompletedToolSummary.of(
                toolName, fingerprint, safe(summaryText), rowCount, stepId
        );
        context.addCompletedToolSummary(completed);

        // Capture artifact content onto the current step so PlanStepRuleEvaluator can verify
        // the skill's declared heading anchors without having to re-parse raw outcomes.
        if (toolName.startsWith("artifact.") && outcome.success()) {
            String content = extractArtifactContent(outcome);
            PlanStep target = context.runPlan().currentInProgressStep().orElse(autoAdvanced);
            if (target != null) {
                if (!content.isBlank()) {
                    target.setArtifactContent(content);
                }
                // Attach summary explicitly — addCompletedToolSummary attaches only to the
                // IN_PROGRESS step, which is empty in the auto-advance path above.
                if (autoAdvanced == target && target.status() != PlanStatus.COMPLETED) {
                    target.attachToolSummary(completed);
                    target.updateStatus(PlanStatus.COMPLETED);
                    if (target.resultNote() == null || target.resultNote().isBlank()) {
                        target.updateResultNote("(auto-advanced by backend: "
                                + toolName + " succeeded)");
                    }
                    emitPlanSnapshot(context);
                }
            }
        }
    }

    /**
     * Find the first non-COMPLETED plan step whose toolHint declares this artifact tool.
     * Used when the LLM issued artifact.* without first calling plan.update IN_PROGRESS —
     * we want to auto-advance that step to COMPLETED since the deliverable is already produced.
     */
    private java.util.Optional<PlanStep> findMatchingArtifactStep(ToolLoopContext context, String toolName) {
        if (context == null || toolName == null || !toolName.startsWith("artifact.")) {
            return java.util.Optional.empty();
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        for (PlanStep step : context.runPlan().steps()) {
            if (step.status() == PlanStatus.COMPLETED || step.status() == PlanStatus.SKIPPED) {
                continue;
            }
            String hint = step.toolHint() == null ? "" : step.toolHint().toLowerCase(Locale.ROOT);
            if (hint.contains(normalized) || hint.contains("artifact.")) {
                return java.util.Optional.of(step);
            }
        }
        return java.util.Optional.empty();
    }

    private void emitPlanSnapshot(ToolLoopContext context) {
        try {
            String payload = objectMapper.writeValueAsString(context.runPlan().toSnapshot());
            context.emitEvent(AgentEventType.PLAN_UPDATED, payload);
        } catch (Exception ignored) {
        }
    }

    /**
     * Render the first 1–2 rows of a db.query result as a compact "col=val, col=val" preview
     * that fits inside the CompletedToolSummary text. Returns empty for large result sets so
     * we don't flood the prompt.
     */
    private String extractResultPreview(ToolCallOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null) {
            return "";
        }
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson == null || dataJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            JsonNode rows = root.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                return "";
            }
            int rowBudget = Math.min(rows.size(), 2);
            int colBudget = 10;
            StringBuilder builder = new StringBuilder();
            for (int r = 0; r < rowBudget; r++) {
                JsonNode row = rows.get(r);
                if (row == null || !row.isObject()) {
                    continue;
                }
                if (r > 0) {
                    builder.append(" | ");
                }
                int cols = 0;
                var fields = row.fields();
                while (fields.hasNext() && cols < colBudget) {
                    var entry = fields.next();
                    if (cols > 0) {
                        builder.append(", ");
                    }
                    builder.append(entry.getKey()).append("=");
                    JsonNode val = entry.getValue();
                    String raw = val == null || val.isNull() ? "null" : val.asText("");
                    builder.append(raw.length() > 60 ? raw.substring(0, 60) + "…" : raw);
                    cols++;
                }
                if (fields.hasNext()) {
                    builder.append(", …");
                }
            }
            if (rows.size() > rowBudget) {
                builder.append(" (+").append(rows.size() - rowBudget).append(" more rows)");
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String extractArtifactContent(ToolCallOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null) {
            return "";
        }
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson == null || dataJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            // Artifact tools store the generated body under one of these keys depending on kind.
            for (String field : new String[]{"markdown", "content", "text", "body"}) {
                JsonNode node = root.path(field);
                if (node.isTextual()) {
                    String value = node.asText("");
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private int extractRowCount(ToolCallOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null) {
            return 0;
        }
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson == null || dataJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            JsonNode rows = root.path("rows");
            if (rows.isArray()) {
                return rows.size();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private String argumentsFingerprint(String toolName, String argumentsJson) {
        try {
            JsonNode node = objectMapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            if (node.isObject() && node.has("sql")) {
                String sql = node.path("sql").asText("");
                return toolName + ":sql:" + canonicalizeSql(sql);
            }
            if (node.isObject() && node.has("table")) {
                String table = node.path("table").asText("").trim().toLowerCase(Locale.ROOT);
                String schema = node.path("schema").asText("").trim().toLowerCase(Locale.ROOT);
                return toolName + ":table:" + (schema.isBlank() ? table : schema + "." + table);
            }
        } catch (Exception ignored) {
        }
        return toolName + ":raw:" + safeNullable(argumentsJson).trim()
                .toLowerCase(Locale.ROOT);
    }

    private String canonicalizeSql(String sql) {
        if (sql == null) return "";
        String stripped = sql.trim();
        if (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        String noAlias = SQL_ALIAS_PATTERN.matcher(stripped).replaceAll(" ");
        String noOrder = SQL_ORDER_BY_PATTERN.matcher(noAlias).replaceAll(" ");
        String tightPunct = SQL_SPACE_BEFORE_PUNCT.matcher(noOrder).replaceAll("$1");
        return WHITESPACE_PATTERN.matcher(tightPunct).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }
}
