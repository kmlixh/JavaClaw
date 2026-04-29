package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.tool.policy.ToolPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端核对 G 产物链路中 DefaultToolLoopPolicy 的两道硬闸：
 * <ol>
 *   <li>skill 声明 requiredPlanStepIds 时，empty plan 下的任意 {@code artifact.*} 调用必须被拒绝，
 *       并强制 LLM 先走 plan.create（resource+empty-plan gate 的核心）；</li>
 *   <li>skill 只在 requiresSuccess 中列出 {@code artifact.markdown} 时，任何其他 artifact.* 工具
 *       （例如 artifact.word/pptx/excel）都必须被拒绝；</li>
 * </ol>
 * 这两条是支撑"自动注入 datasource 资源 + 不让 LLM 绕过计划"整条路径的前置条件 —
 * 如果这里失守，ChatSend 只需直接下发 artifact.word 就能拿到产物，前面的 resource/plan 机制等于白做。
 */
class DefaultToolLoopPolicyTest {

    private DefaultToolLoopPolicy policy;

    @BeforeEach
    void setUp() {
        ToolPolicyService alwaysAllow = (agentId, toolName) -> true;
        ApprovalRequirementService noApproval = (context, toolName) -> false;
        this.policy = new DefaultToolLoopPolicy(noApproval, alwaysAllow);
    }

    @Test
    void emptyPlanBlocksArtifactCallWhenSkillDeclaresPlanEnforcement() {
        SkillGuard guard = buildCoverageGuard();
        ToolLoopContext context = newContext(guard);

        ToolCallRequest req = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "artifact.markdown",
                "{\"name\":\"report.md\",\"content\":\"# 覆盖分析报告\\n..."
                        + "正文\"}",
                ""
        );

        ToolCallDecision decision = policy.evaluate(context, req);

        assertFalse(decision.allowed(),
                "artifact.markdown must be rejected before any plan.create is issued");
        assertNotNull(decision.reason());
        assertTrue(decision.reason().contains("plan.create"),
                "rejection should tell the LLM to call plan.create first. reason=" + decision.reason());
        assertTrue(decision.reason().contains("[sector, weak_area, coverage, weak_grid, report]")
                        || decision.reason().contains("sector"),
                "rejection should echo the required plan step ids. reason=" + decision.reason());
    }

    @Test
    void artifactWordBlockedEvenWhenPlanIsPresent() {
        SkillGuard guard = buildCoverageGuard();
        ToolLoopContext context = newContext(guard);
        // Seed a populated plan so the empty-plan gate does NOT fire — we must still reject
        // artifact.word because the skill's allowedArtifactTools only contains artifact.markdown.
        context.runPlan().addStep(new PlanStep(
                "report", "生成 markdown 报告", "artifact.markdown", ".md"));

        ToolCallRequest req = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "artifact.word",
                "{\"name\":\"report.docx\"}",
                ""
        );

        ToolCallDecision decision = policy.evaluate(context, req);

        assertFalse(decision.allowed(),
                "skill restricts artifact output to markdown; artifact.word must be rejected");
        assertNotNull(decision.reason());
        assertTrue(decision.reason().contains("artifact.markdown")
                        && decision.reason().contains("artifact.word"),
                "rejection should name the allowed tool (artifact.markdown) and the refused one (artifact.word). reason="
                        + decision.reason());
    }

    @Test
    void artifactMarkdownAllowedOnceRequiredPlanExists() {
        SkillGuard guard = buildCoverageGuard();
        ToolLoopContext context = newContext(guard);
        PlanStep reportStep = new PlanStep(
                "report", "生成 markdown 报告", "artifact.markdown", ".md");
        // plan 推进守卫现在要求"调 artifact.* 时必须有 IN_PROGRESS step",对应真实运行时
        // 顺序(plan.update IN_PROGRESS → 调工具)。手动推进到 IN_PROGRESS 模拟 LLM 已经
        // 走完前置 plan.update。
        reportStep.updateStatus(PlanStatus.IN_PROGRESS);
        context.runPlan().addStep(reportStep);

        ToolCallRequest req = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "artifact.markdown",
                "{\"name\":\"coverage.md\"}",
                ""
        );

        ToolCallDecision decision = policy.evaluate(context, req);

        assertTrue(decision.allowed(),
                "with a non-empty plan and artifact.markdown in the whitelist the call must pass. reason="
                        + decision.reason());
        assertFalse(decision.approvalRequired());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void nonArtifactToolsAreUnaffectedByTheArtifactGate() {
        SkillGuard guard = buildCoverageGuard();
        ToolLoopContext context = newContext(guard);
        // Empty plan; db.query should still pass the policy — the empty-plan nudge only
        // touches artifact.* so the LLM can still do its reconnaissance queries before
        // creating the plan.
        ToolCallRequest req = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "db.query",
                "{\"sql\":\"select 1\"}",
                ""
        );

        ToolCallDecision decision = policy.evaluate(context, req);
        assertTrue(decision.allowed(),
                "db.query must not be blocked by the artifact gate. reason=" + decision.reason());
    }

    private static ToolLoopContext newContext(SkillGuard guard) {
        RunRequest request = new RunRequest(
                "policy-test-run-" + UUID.randomUUID(),
                "policy-test-sess-" + UUID.randomUUID(),
                "dev-agent",
                "junit",
                "对昆明主城进行覆盖分析",
                false,
                null,
                null,
                List.of(),
                List.of()
        );
        ToolLoopContext context = new ToolLoopContext(
                request,
                request.runId(),
                new PromptContext("system", "user prompt"),
                100
        );
        context.setGuard(guard);
        return context;
    }

    /**
     * Mirrors the coverage-analysis skill shape: 5 required plan step IDs, and the report
     * step demands a successful {@code artifact.markdown} invocation. Whitelist is
     * non-empty only so hasTableEnforcement() is realistic — tables themselves aren't
     * exercised by the artifact gate assertions.
     */
    private static SkillGuard buildCoverageGuard() {
        PlanStepRule reportRule = new PlanStepRule(
                List.of("artifact.markdown"),      // requiresSuccess
                List.of(),                         // tableAllowList
                0,                                  // minQueries
                true,                               // zeroRowsAllowed
                List.of(),                         // mustMatchTemplateAnchors
                "",                                 // reuseStep
                List.of(),                         // sqlTemplates
                List.of(),                         // sqlTemplatesGeoJson
                List.of(),                         // sqlTemplatesNoFilter
                null,                               // reportSection
                "",                                 // jdbcUrl
                List.of(),                         // requiredTables
                List.of(),                         // dependsOn
                false,                              // dependsOnDeclared
                PlanStepRule.Acceptance.NONE
        );
        return new SkillGuard(
                java.util.Set.of("xmap.layer_wy_5g_cell_info_section_yn"),
                java.util.Set.of(),
                List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                Map.of("report", reportRule),
                List.of("skill.coverage.analysis")
        );
    }
}
