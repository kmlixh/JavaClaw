package com.janyee.agent.runtime.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.loop.CompletedToolSummary;
import com.janyee.agent.runtime.loop.PlanStep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stateless evaluator that converts a {@link PlanStepRule} plus a step's accumulated tool
 * summaries (and optionally the artifact content produced in this run) into a
 * human-readable list of completion violations. An empty list means the step may legally
 * transition to COMPLETED.
 *
 * Kept small and side-effect-free so both PlanUpdateTool (on every COMPLETED transition)
 * and DefaultToolLoopOrchestrator (on run-end gate) can call it without coupling.
 */
public final class PlanStepRuleEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanStepRuleEvaluator() {
    }

    public static List<String> evaluateCompletion(PlanStep step, PlanStepRule rule) {
        return evaluateCompletion(step, rule, null);
    }

    /**
     * Evaluate whether the step's accumulated work satisfies the skill's declared rule.
     * Returns violations as user-facing messages (empty = OK).
     *
     * When the rule points at another step via {@code reuseStep}, resolution is handled by
     * {@link SkillGuard#stepRule(String)} — the caller passes the effective rule here.
     *
     * @param latestArtifactContent the markdown / text content of the most recent successful
     *     {@code artifact.*} invocation in this run. Required to check
     *     {@link PlanStepRule#reportSection()} heading anchors; may be null if no artifact
     *     has been produced or the check is not relevant.
     */
    public static List<String> evaluateCompletion(PlanStep step, PlanStepRule rule, String latestArtifactContent) {
        List<String> violations = new ArrayList<>();
        if (step == null || rule == null || rule.isEmpty()) {
            return violations;
        }
        List<CompletedToolSummary> summaries = step.toolSummaries();

        if (!rule.requiresSuccess().isEmpty()) {
            boolean matched = rule.requiresSuccess().stream().anyMatch(required ->
                    summaries.stream().anyMatch(s -> required.equals(s.toolName()))
            );
            if (!matched) {
                violations.add("step '" + step.id() + "' requires a successful invocation of one of "
                        + rule.requiresSuccess() + "; none recorded yet");
            }
        }

        if (rule.minQueries() > 0) {
            long queryCount = summaries.stream()
                    .filter(s -> "db.query".equals(s.toolName()))
                    .count();
            if (queryCount < rule.minQueries()) {
                violations.add("step '" + step.id() + "' requires at least " + rule.minQueries()
                        + " db.query result(s); got " + queryCount);
            }
        }

        // requiredTables：skill 声明了必须查到哪些 schema.table 才算完整，evaluator 逐一核对。
        // CompletedToolSummary.argumentsFingerprint() 里存的是规范化后的小写 SQL，含 schema.table 字面量；
        // 我们对每个 required table 在所有 db.query 的指纹中做子串搜索 —— 只要有一次成功 db.query
        // 触到了该表就算满足。这样堵住了 "只查 1 张，其它靠记忆" 的漏洞。
        if (!rule.requiredTables().isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (String requiredTable : rule.requiredTables()) {
                if (requiredTable == null || requiredTable.isBlank()) {
                    continue;
                }
                String needle = requiredTable.toLowerCase(Locale.ROOT).trim();
                boolean touched = summaries.stream()
                        .filter(s -> "db.query".equals(s.toolName()))
                        .anyMatch(s -> {
                            String fp = s.argumentsFingerprint();
                            return fp != null && fp.contains(needle);
                        });
                if (!touched) {
                    missing.add(requiredTable);
                }
            }
            if (!missing.isEmpty()) {
                violations.add("step '" + step.id() + "' still missing successful db.query on required table(s) "
                        + missing + "; do not fabricate values — query each listed table once before COMPLETED.");
            }
        }

        // Acceptance:基于 step 累积的每条 db.query summary 的 firstRowJson 做字段级校验。
        // 这是 user 明确要求的"step 完成前要校验工作结果是否真完成"——之前 requiredTables 只
        // 保证了"查了哪几张表",没有保证"查回来的字段对不对、值是不是全 0"。
        PlanStepRule.Acceptance acceptance = rule.acceptance();
        if (acceptance != null && !acceptance.isEmpty()) {
            List<JsonNode> rowNodes = parseFirstRows(summaries);
            if (!acceptance.requiredColumns().isEmpty()) {
                Set<String> seenColumns = collectColumnNames(rowNodes);
                List<String> missingCols = new ArrayList<>();
                for (String col : acceptance.requiredColumns()) {
                    if (col == null || col.isBlank()) continue;
                    if (!seenColumns.contains(col.toLowerCase(Locale.ROOT).trim())) {
                        missingCols.add(col);
                    }
                }
                if (!missingCols.isEmpty()) {
                    violations.add("step '" + step.id() + "' acceptance failed: required columns "
                            + missingCols + " not present in any successful db.query result. "
                            + "Re-run with a SELECT list that includes these columns by name (the report needs them).");
                }
            }
            if (acceptance.requireNonZeroData()) {
                if (!hasAnyNonZeroNumeric(rowNodes)) {
                    violations.add("step '" + step.id() + "' acceptance failed: all numeric values "
                            + "across this step's db.query results are zero/null. "
                            + "Either the region filter produced an empty result set (re-query with a different filter "
                            + "or mark this step SKIPPED with resultNote='no data in region'), or the SELECT list "
                            + "is wrong (no aggregate column was returned). Do NOT propagate zeros into the artifact.");
                }
            }
        }

        if (!rule.zeroRowsAllowed()) {
            boolean hasNonZero = summaries.stream()
                    .filter(s -> "db.query".equals(s.toolName()))
                    .anyMatch(s -> s.rowCount() > 0);
            if (!hasNonZero) {
                violations.add("step '" + step.id() + "' disallows zero-row-only completion; re-query with a filter that returns data, or flip to SKIPPED with resultNote");
            }
        }

        PlanStepRule.ReportSection reportSection = rule.reportSection();
        if (reportSection != null) {
            // Only enforce heading/placeholder anchors when this step actually produced an
            // artifact — i.e. when requiresSuccess names an artifact.* tool. Non-report
            // steps just carry reportSection as descriptive context for the LLM.
            boolean stepIsReport = rule.requiresSuccess().stream()
                    .anyMatch(tool -> tool != null && tool.startsWith("artifact."));
            if (stepIsReport) {
                if (latestArtifactContent == null || latestArtifactContent.isBlank()) {
                    if (!reportSection.heading().isBlank() || !reportSection.placeholders().isEmpty()) {
                        violations.add("step '" + step.id() + "' must produce an artifact whose content matches the skill's reportSection; no artifact content captured yet");
                    }
                } else {
                    // 校验前先 normalize:把双方所有空白字符(含中文全角空格 / NBSP)压平,
                    // 这样 "2G扇区" / "2G 扇区" / "2G 扇区" 互相能命中。LLM 在中文/英文/数字
                    // 之间加不加空格属于格式微差,不该让校验把整轮 run 卡住。
                    String normalizedContent = normalizeForAnchorMatch(latestArtifactContent);
                    if (!reportSection.heading().isBlank()) {
                        // heading 校验有两层放宽:
                        //   1) 整体 heading (含 "# " 前缀) normalize 后 contains;
                        //   2) 退一步,只看 heading 去掉 markdown 前缀的纯文本是否被 content 包含 ——
                        //      LLM 写更具体的 heading (如 "# 昆明市西山区综合覆盖分析报告") 包含
                        //      skill 要求的关键词 "覆盖分析报告",应该视为合规。
                        String normHeading = normalizeForAnchorMatch(reportSection.heading());
                        String headingText = reportSection.heading().replaceFirst("^#+\\s*", "").trim();
                        String normHeadingText = normalizeForAnchorMatch(headingText);
                        boolean matched = (!normHeading.isEmpty() && normalizedContent.contains(normHeading))
                                || (!normHeadingText.isEmpty() && normalizedContent.contains(normHeadingText));
                        if (!matched) {
                            violations.add("step '" + step.id() + "' artifact is missing required heading '"
                                    + reportSection.heading() + "' — follow the skill's markdown template exactly");
                        }
                    }
                    // Every declared placeholder label must appear somewhere in the artifact;
                    // this catches the classic "LLM only wrote 3 of 4 bullets" failure.
                    for (PlanStepRule.Placeholder placeholder : reportSection.placeholders()) {
                        String label = placeholder.label();
                        if (label == null || label.isBlank()) {
                            continue;
                        }
                        String normLabel = normalizeForAnchorMatch(label);
                        if (normLabel.isEmpty()) continue;
                        if (!normalizedContent.contains(normLabel)) {
                            violations.add("step '" + step.id() + "' artifact is missing required anchor '"
                                    + label + "' — skill's reportSection requires it");
                        }
                    }
                }
            }
        }

        return violations;
    }

    /**
     * Convenience: resolve the guard's rule for a step, evaluate, and return a single-line
     * reason string (or empty Optional if no violation).
     */
    public static Optional<String> firstViolation(PlanStep step, SkillGuard guard) {
        if (step == null || guard == null) {
            return Optional.empty();
        }
        PlanStepRule rule = guard.stepRule(step.id()).orElse(null);
        if (rule == null) {
            return Optional.empty();
        }
        List<String> violations = evaluateCompletion(step, rule);
        return violations.isEmpty() ? Optional.empty() : Optional.of(violations.get(0));
    }

    /**
     * 字符串规范化用于锚点匹配:把所有 Java 空白字符(\\s) + 中文全角空格 \\u3000 +
     * 不间断空格 \\u00A0 等都去掉。这样 "OTT 5G 弱覆盖" / "OTT5G弱覆盖" / "OTT 5G弱覆盖"
     * 视为同一锚点,LLM 在排版上的格式微差不会让 run 卡死在 plan.update 校验循环里。
     */
    private static String normalizeForAnchorMatch(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\s\\u00A0\\u3000]+", "");
    }

    /**
     * 把 step 的所有成功 db.query summary 的 firstRowJson 解析成 JsonNode list。解析失败 / 没
     * firstRow 的 summary 跳过。结果只用于 acceptance 校验,不会影响其它流程。
     */
    private static List<JsonNode> parseFirstRows(List<CompletedToolSummary> summaries) {
        List<JsonNode> rows = new ArrayList<>();
        if (summaries == null || summaries.isEmpty()) {
            return rows;
        }
        for (CompletedToolSummary s : summaries) {
            if (s == null || !"db.query".equals(s.toolName())) continue;
            String json = s.firstRowJson();
            if (json == null || json.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(json);
                if (node != null && node.isObject()) {
                    rows.add(node);
                }
            } catch (Exception ignored) {
                // 单条解析失败不阻塞整体,acceptance 是尽力而为的校验。
            }
        }
        return rows;
    }

    /** 收集所有 row 的 key,统一小写化做大小写无关的列名比较。 */
    private static Set<String> collectColumnNames(List<JsonNode> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            row.fieldNames().forEachRemaining(name -> {
                if (name != null && !name.isBlank()) {
                    names.add(name.toLowerCase(Locale.ROOT).trim());
                }
            });
        }
        return names;
    }

    /**
     * 检查每个 row 的所有数值字段(int/long/double/decimal):只要发现至少一个 > 0,就算通过。
     * 字符串里的数字也尝试解析(LLM/JDBC 偶尔把数字当 text 返回)。null / 0 / 非数字字符串都
     * 算"无数据信号"。
     */
    private static boolean hasAnyNonZeroNumeric(List<JsonNode> rows) {
        for (JsonNode row : rows) {
            var fields = row.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value == null || value.isNull()) continue;
                if (value.isNumber()) {
                    if (value.doubleValue() > 0d) return true;
                    continue;
                }
                if (value.isTextual()) {
                    String text = value.asText("").trim();
                    if (text.isEmpty()) continue;
                    try {
                        if (Double.parseDouble(text) > 0d) return true;
                    } catch (NumberFormatException ignored) {
                        // 非数字字符串忽略
                    }
                }
            }
        }
        return false;
    }
}
