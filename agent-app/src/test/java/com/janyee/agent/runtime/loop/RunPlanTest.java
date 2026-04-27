package com.janyee.agent.runtime.loop;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunPlanTest {

    @Test
    void addStepAndLookup() {
        RunPlan plan = new RunPlan();
        plan.setTitle("coverage analysis");
        plan.addStep(new PlanStep("s1", "扇区统计", "db.query", "2G/4G/5G 数量"));
        plan.addStep(new PlanStep("s2", "弱覆盖区域", "db.query", "grid_count >=3"));

        assertEquals(2, plan.size());
        assertTrue(plan.find("s1").isPresent());
        assertEquals("扇区统计", plan.find("s1").get().title());
        assertEquals(PlanStatus.PENDING, plan.find("s1").get().status());
    }

    @Test
    void currentInProgressStepReturnsFirstOnly() {
        RunPlan plan = new RunPlan();
        PlanStep s1 = plan.addStep(new PlanStep("s1", "扇区", "db.query", ""));
        PlanStep s2 = plan.addStep(new PlanStep("s2", "覆盖率", "db.query", ""));

        assertTrue(plan.currentInProgressStep().isEmpty());
        s1.updateStatus(PlanStatus.IN_PROGRESS);
        assertEquals("s1", plan.currentInProgressStep().orElseThrow().id());
        s1.updateStatus(PlanStatus.COMPLETED);
        s2.updateStatus(PlanStatus.IN_PROGRESS);
        assertEquals("s2", plan.currentInProgressStep().orElseThrow().id());
    }

    @Test
    void duplicateIdRejected() {
        RunPlan plan = new RunPlan();
        plan.addStep(new PlanStep("dup", "first", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> plan.addStep(new PlanStep("dup", "second", "", "")));
    }

    @Test
    void hasUnfinishedStepsRecognizesPendingAndInProgress() {
        RunPlan plan = new RunPlan();
        PlanStep s1 = plan.addStep(new PlanStep("s1", "a", "", ""));
        PlanStep s2 = plan.addStep(new PlanStep("s2", "b", "", ""));
        assertTrue(plan.hasUnfinishedSteps());
        s1.updateStatus(PlanStatus.COMPLETED);
        assertTrue(plan.hasUnfinishedSteps());
        s2.updateStatus(PlanStatus.SKIPPED);
        assertFalse(plan.hasUnfinishedSteps());
    }

    @Test
    void renderCompactIncludesStatusAndHint() {
        RunPlan plan = new RunPlan();
        plan.setTitle("coverage");
        PlanStep s1 = plan.addStep(new PlanStep("s1", "扇区", "db.query", ""));
        s1.updateStatus(PlanStatus.IN_PROGRESS);
        plan.addStep(new PlanStep("s2", "报告", "artifact.markdown", ""));

        String rendered = plan.renderCompact();
        assertTrue(rendered.contains("plan: coverage"));
        assertTrue(rendered.contains("[IN_PROGRESS] s1: 扇区 | tool=db.query"));
        assertTrue(rendered.contains("[PENDING] s2: 报告 | tool=artifact.markdown"));
    }

    @Test
    void attachToolSummaryGrowsOutputsCounter() {
        RunPlan plan = new RunPlan();
        PlanStep step = plan.addStep(new PlanStep("s1", "扇区", "db.query", ""));
        step.updateStatus(PlanStatus.IN_PROGRESS);
        step.attachToolSummary(CompletedToolSummary.of("db.query", "fp-1", "3 rows", 3, "s1"));
        step.attachToolSummary(CompletedToolSummary.of("db.query", "fp-2", "6 rows", 6, "s1"));
        assertEquals(2, step.toolSummaries().size());
        assertTrue(plan.renderCompact().contains("outputs=2"));
    }

    @Test
    void findByBlankIdReturnsEmpty() {
        RunPlan plan = new RunPlan();
        plan.addStep(new PlanStep("s1", "x", "", ""));
        assertEquals(Optional.empty(), plan.find(""));
        assertEquals(Optional.empty(), plan.find(null));
    }
}
