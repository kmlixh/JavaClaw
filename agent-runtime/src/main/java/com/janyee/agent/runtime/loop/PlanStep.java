package com.janyee.agent.runtime.loop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlanStep {

    private final String id;
    private String title;
    private String toolHint;
    private String expectedOutput;
    private PlanStatus status;
    private String resultNote;
    private final List<CompletedToolSummary> toolSummaries = new ArrayList<>();
    // Materialized from the active skill's PlanStepRule at plan.create time so the LLM can
    // see "which tables + which SQL + which report slot" directly in the rendered plan,
    // without having to re-read long skill prompt sections mid-run.
    private List<String> allowedTables = List.of();
    private List<String> sqlTemplates = List.of();
    private List<String> sqlTemplatesGeoJson = List.of();
    private List<String> sqlTemplatesNoFilter = List.of();
    private String reportHeading = "";
    private List<String> reportPlaceholders = List.of();
    // Captured by DefaultToolResultAppender when an artifact.* tool completes while this
    // step is IN_PROGRESS, so PlanStepRuleEvaluator can verify required heading anchors.
    private String artifactContent = "";
    private String jdbcUrl = "";
    /**
     * Step IDs that must each be COMPLETED (or SKIPPED) before this step may transition to
     * IN_PROGRESS. Materialized from {@link com.janyee.agent.runtime.skill.PlanStepRule#dependsOn()}
     * at plan-create / plan-seed time, with a default-serial fallback applied by
     * {@code SkillPlanSeeder} / {@code PlanCreateTool} when the skill didn't declare deps —
     * each step gets {@code [previousStepId]} so old skills get strict serial behavior.
     */
    private List<String> dependsOn = List.of();

    public PlanStep(String id, String title, String toolHint, String expectedOutput) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plan step id is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("plan step title is required");
        }
        this.id = id;
        this.title = title;
        this.toolHint = toolHint == null ? "" : toolHint;
        this.expectedOutput = expectedOutput == null ? "" : expectedOutput;
        this.status = PlanStatus.PENDING;
        this.resultNote = "";
    }

    public String id() { return id; }
    public String title() { return title; }
    public String toolHint() { return toolHint; }
    public String expectedOutput() { return expectedOutput; }
    public PlanStatus status() { return status; }
    public String resultNote() { return resultNote; }
    public List<CompletedToolSummary> toolSummaries() { return Collections.unmodifiableList(toolSummaries); }
    public List<String> allowedTables() { return allowedTables; }
    public List<String> sqlTemplates() { return sqlTemplates; }
    public List<String> sqlTemplatesGeoJson() { return sqlTemplatesGeoJson; }
    public List<String> sqlTemplatesNoFilter() { return sqlTemplatesNoFilter; }
    public String reportHeading() { return reportHeading; }
    public List<String> reportPlaceholders() { return reportPlaceholders; }
    public String artifactContent() { return artifactContent; }
    public String jdbcUrl() { return jdbcUrl; }
    public List<String> dependsOn() { return dependsOn; }

    public void updateStatus(PlanStatus next) {
        if (next == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.status = next;
    }

    public void updateTitle(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public void updateToolHint(String toolHint) {
        if (toolHint != null) {
            this.toolHint = toolHint;
        }
    }

    public void updateExpectedOutput(String expectedOutput) {
        if (expectedOutput != null) {
            this.expectedOutput = expectedOutput;
        }
    }

    public void updateResultNote(String note) {
        if (note != null) {
            this.resultNote = note;
        }
    }

    public void attachToolSummary(CompletedToolSummary summary) {
        if (summary != null) {
            toolSummaries.add(summary);
        }
    }

    public void setAllowedTables(List<String> tables) {
        this.allowedTables = tables == null ? List.of() : List.copyOf(tables);
    }

    public void setSqlTemplates(List<String> templates) {
        this.sqlTemplates = templates == null ? List.of() : List.copyOf(templates);
    }

    public void setSqlTemplatesGeoJson(List<String> templates) {
        this.sqlTemplatesGeoJson = templates == null ? List.of() : List.copyOf(templates);
    }

    public void setSqlTemplatesNoFilter(List<String> templates) {
        this.sqlTemplatesNoFilter = templates == null ? List.of() : List.copyOf(templates);
    }

    public void setReportHeading(String heading) {
        this.reportHeading = heading == null ? "" : heading;
    }

    public void setReportPlaceholders(List<String> placeholders) {
        this.reportPlaceholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    public void setArtifactContent(String content) {
        this.artifactContent = content == null ? "" : content;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
