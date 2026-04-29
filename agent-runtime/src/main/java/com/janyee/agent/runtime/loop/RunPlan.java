package com.janyee.agent.runtime.loop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Mutable, single-run plan of work composed of ordered PlanSteps. Not thread safe; the
 * surrounding ToolLoopContext runs one iteration at a time inside a Reactor bounded-elastic
 * scheduler so single-threaded access is sufficient.
 */
public class RunPlan {

    private final List<PlanStep> steps = new ArrayList<>();
    private final Map<String, PlanStep> stepsById = new LinkedHashMap<>();
    private String title = "";

    public String title() { return title; }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public boolean isEmpty() { return steps.isEmpty(); }

    public int size() { return steps.size(); }

    public List<PlanStep> steps() { return Collections.unmodifiableList(steps); }

    public Optional<PlanStep> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stepsById.get(id));
    }

    public PlanStep addStep(PlanStep step) {
        if (step == null) {
            throw new IllegalArgumentException("step must not be null");
        }
        if (stepsById.containsKey(step.id())) {
            throw new IllegalArgumentException("duplicate step id: " + step.id());
        }
        steps.add(step);
        stepsById.put(step.id(), step);
        return step;
    }

    /** First step in IN_PROGRESS state, or empty. */
    public Optional<PlanStep> currentInProgressStep() {
        for (PlanStep step : steps) {
            if (step.status() == PlanStatus.IN_PROGRESS) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /**
     * Step IDs in {@code step.dependsOn()} that are NOT yet COMPLETED or SKIPPED. An empty
     * list means the step is unblocked and may transition to IN_PROGRESS. Unknown step IDs
     * are treated as unmet (defensive — a typo in skill JSON shouldn't silently let the LLM
     * skip a real predecessor).
     */
    public List<String> unmetDependencies(PlanStep step) {
        if (step == null || step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return List.of();
        }
        List<String> unmet = new ArrayList<>();
        for (String depId : step.dependsOn()) {
            if (depId == null || depId.isBlank()) {
                continue;
            }
            PlanStep dep = stepsById.get(depId);
            if (dep == null) {
                unmet.add(depId + " (unknown step id)");
                continue;
            }
            PlanStatus s = dep.status();
            if (s != PlanStatus.COMPLETED && s != PlanStatus.SKIPPED) {
                unmet.add(depId + " (" + s.name() + ")");
            }
        }
        return unmet;
    }

    /**
     * Whether {@code step} can legally transition to IN_PROGRESS right now. False when any
     * declared dependency is still PENDING / IN_PROGRESS / FAILED.
     */
    public boolean isReady(PlanStep step) {
        return unmetDependencies(step).isEmpty();
    }

    public boolean hasUnfinishedSteps() {
        for (PlanStep step : steps) {
            PlanStatus status = step.status();
            if (status == PlanStatus.PENDING || status == PlanStatus.IN_PROGRESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emit a JSON-friendly snapshot consumable by the UI. Kept deliberately flat and small
     * so it can travel on every plan.* tool completion without bloating the event stream.
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", title);
        snapshot.put("steps", steps.stream().map(step -> {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("id", step.id());
            stepMap.put("title", step.title());
            stepMap.put("toolHint", step.toolHint());
            stepMap.put("expectedOutput", step.expectedOutput());
            stepMap.put("status", step.status().name());
            stepMap.put("resultNote", step.resultNote());
            stepMap.put("outputCount", step.toolSummaries().size());
            stepMap.put("dependsOn", step.dependsOn());
            return stepMap;
        }).collect(Collectors.toList()));
        return snapshot;
    }

    public String renderCompact() {
        if (steps.isEmpty()) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder();
        if (!title.isBlank()) {
            builder.append("plan: ").append(title).append('\n');
        }
        for (PlanStep step : steps) {
            builder.append("- [").append(step.status().name()).append("] ")
                    .append(step.id()).append(": ").append(step.title());
            if (!step.toolHint().isBlank()) {
                builder.append(" | tool=").append(step.toolHint());
            }
            // dependsOn 直接渲染到 plan compact 视图 —— LLM 需要看到"我必须先等谁",而不是
            // 等 plan.update 拒绝时才知道。每行末尾一段简短的 deps=[A,B] 标识。
            if (!step.dependsOn().isEmpty()) {
                builder.append(" | deps=").append(step.dependsOn());
            }
            if (!step.resultNote().isBlank()) {
                builder.append(" | note=").append(truncate(step.resultNote(), 120));
            }
            if (!step.toolSummaries().isEmpty()) {
                builder.append(" | outputs=").append(step.toolSummaries().size());
            }
            builder.append('\n');
            if (!step.jdbcUrl().isBlank()) {
                builder.append("    jdbcUrl: ").append(step.jdbcUrl()).append('\n');
            }
            if (!step.allowedTables().isEmpty()) {
                builder.append("    tables: ").append(String.join(", ", step.allowedTables())).append('\n');
            }
            // 三套 SQL 按决策优先级渲染：geojson > admin > no-filter。
            // LLM 按用户消息内容逐级匹配 —— 先看有没有 GeoJSON，再看有没有地名，都没有才用 no-filter。
            if (!step.sqlTemplatesGeoJson().isEmpty()) {
                builder.append("    sql (geojson filter) — 用户消息含 \"type\":\"Polygon\"/\"MultiPolygon\" 时用:\n");
                for (String template : step.sqlTemplatesGeoJson()) {
                    builder.append("      | ").append(template.replace("\n", " ")).append('\n');
                }
            }
            if (!step.sqlTemplates().isEmpty()) {
                builder.append("    sql (admin filter) — 用户给了中文地名（城市/区县）时用:\n");
                for (String template : step.sqlTemplates()) {
                    builder.append("      | ").append(template.replace("\n", " ")).append('\n');
                }
            }
            if (!step.sqlTemplatesNoFilter().isEmpty()) {
                builder.append("    sql (no filter) — **兜底**，用户没给 GeoJSON 也没给地名时**强制用这套**:\n");
                for (String template : step.sqlTemplatesNoFilter()) {
                    builder.append("      | ").append(template.replace("\n", " ")).append('\n');
                }
            }
            if (!step.reportHeading().isBlank()) {
                builder.append("    report-section: ").append(step.reportHeading());
                if (!step.reportPlaceholders().isEmpty()) {
                    builder.append(" (placeholders: ").append(String.join("; ", step.reportPlaceholders())).append(")");
                }
                builder.append('\n');
            }
        }
        return builder.toString().stripTrailing();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
