package com.janyee.agent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.infra.tool.PlanCreateTool;
import com.janyee.agent.infra.tool.PlanUpdateTool;
import com.janyee.agent.runtime.loop.CompletedToolSummary;
import com.janyee.agent.runtime.loop.PlanStatus;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.loop.RunPlanStore;
import com.janyee.agent.runtime.loop.ToolCallDecision;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolCallRequest;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.runtime.loop.ToolResultAppender;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.runtime.skill.SkillGuardStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证 Plan-Execute + Completed Queries 摘要：
 *  1) plan.create 建 plan，plan.update 切换 step 状态
 *  2) 模拟 db.query 成功：CompletedToolSummary 写入 context 且挂到当前 IN_PROGRESS step
 *  3) currentPrompt() 注入 [Plan] + [Completed Queries] 段，且 SQL 列别名不同但语义相同被归一化到同一指纹
 */
@SpringBootTest
@ActiveProfiles("postgres")
class PlanDrivenLoopTest {

    @Autowired
    private PlanCreateTool planCreateTool;

    @Autowired
    private PlanUpdateTool planUpdateTool;

    @Autowired
    private RunPlanStore runPlanStore;

    @Autowired
    private ToolResultAppender toolResultAppender;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SkillGuardStore skillGuardStore;

    private String runId;
    private ToolLoopContext context;

    @BeforeEach
    void setUp() {
        runId = "plan-run-" + UUID.randomUUID();
        RunRequest request = new RunRequest(
                runId,
                "plan-sess-" + UUID.randomUUID(),
                "dev-agent",
                "junit",
                "cover plan",
                false, null, null, List.of(), List.of()
        );
        context = new ToolLoopContext(
                request,
                runId,
                new PromptContext("system", "original user prompt"),
                100
        );
        runPlanStore.register(runId, context.runPlan());
    }

    @Test
    void planCreateRegistersStepsAndRejectsDuplicateCreate() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "title", "coverage analysis",
                "steps", List.of(
                        Map.of("id", "sector", "title", "统计扇区数量", "toolHint", "db.query", "expectedOutput", "N 条"),
                        Map.of("id", "weak", "title", "弱覆盖区域", "toolHint", "db.query", "expectedOutput", "grid_count"),
                        Map.of("id", "report", "title", "生成 markdown 报告", "toolHint", "artifact.markdown", "expectedOutput", ".md")
                )
        ));

        ToolResult created = planCreateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create", payload));
        assertTrue(created.ok(), "plan.create should succeed: " + created.error());

        RunPlan plan = runPlanStore.find(runId).orElseThrow();
        assertEquals(3, plan.size());
        assertEquals("coverage analysis", plan.title());
        assertEquals(PlanStatus.PENDING, plan.find("sector").orElseThrow().status());

        // 二次 create 应幂等成功，返回 alreadyCreated=true，防止 LLM 反复重试
        ToolResult again = planCreateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create", payload));
        assertTrue(again.ok(), "second plan.create must be idempotent (ok=true)");
        JsonNode data = objectMapper.readTree(again.dataJson());
        assertTrue(data.path("alreadyCreated").asBoolean(),
                "idempotent response should carry alreadyCreated=true");
        assertEquals(3, data.path("stepCount").asInt());
    }

    @Test
    void planCreateAutoRenamesStepIdsWhenCountMatchesSkillRequirement() throws Exception {
        // 场景：LLM 用通用命名 step-1..step-5 创建 plan —— 数量对，但 id 不符 skill 要求。
        // 以前会被 rejectPlanStepIds 挡回去浪费 1 个 iteration；现在按序自动改名为
        // sector/weak_area/coverage/weak_grid/report，直接通过。
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(), java.util.Set.of(),
                List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                Map.of(), List.of("skill.coverage.analysis")
        );
        skillGuardStore.register(runId, guard);
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "title", "盘龙区覆盖分析",
                    "steps", List.of(
                            Map.of("id", "step-1", "title", "扇区统计", "toolHint", "db.query"),
                            Map.of("id", "step-2", "title", "弱覆盖区域", "toolHint", "db.query"),
                            Map.of("id", "step-3", "title", "覆盖率", "toolHint", "db.query"),
                            Map.of("id", "step-4", "title", "弱栅格", "toolHint", "db.query"),
                            Map.of("id", "step-5", "title", "报告", "toolHint", "artifact.markdown")
                    )
            ));
            ToolResult r = planCreateTool.execute(
                    new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create", payload));
            assertTrue(r.ok(), "数量匹配时应自动改名通过: " + r.error());
            JsonNode data = objectMapper.readTree(r.dataJson());
            assertTrue(data.has("autoRenamedStepIds"),
                    "应在 dataJson 标注已自动改名，便于前端/LLM 提示");
            assertTrue(r.summary().contains("auto-renamed"),
                    "summary 应说明发生了自动改名: " + r.summary());

            RunPlan plan = runPlanStore.find(runId).orElseThrow();
            assertEquals(List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                    plan.steps().stream().map(PlanStep::id).toList(),
                    "step id 应被按序改名成 skill 要求的精确列表");
            // 原始标题应保留
            assertEquals("扇区统计", plan.find("sector").orElseThrow().title());
            assertEquals("报告", plan.find("report").orElseThrow().title());
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void planCreateStillRejectsWhenStepCountDiffers() throws Exception {
        // 数量不匹配（LLM 以为该规划 8 步，skill 要求 5 步）说明 LLM 对任务分解的理解
        // 本身就错了，这种情况下必须拒绝让它重新规划 —— 自动改名不能掩盖。
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(), java.util.Set.of(),
                List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                Map.of(), List.of("skill.coverage.analysis")
        );
        skillGuardStore.register(runId, guard);
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "title", "过细粒度",
                    "steps", List.of(
                            Map.of("id", "s1", "title", "a"),
                            Map.of("id", "s2", "title", "b"),
                            Map.of("id", "s3", "title", "c"),
                            Map.of("id", "s4", "title", "d"),
                            Map.of("id", "s5", "title", "e"),
                            Map.of("id", "s6", "title", "f"),
                            Map.of("id", "s7", "title", "g"),
                            Map.of("id", "s8", "title", "h")
                    )
            ));
            ToolResult r = planCreateTool.execute(
                    new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create", payload));
            assertFalse(r.ok(), "8 步 vs 5 步时必须拒绝，不能自动改名掩盖粒度错误");
            assertTrue(r.error().contains("[sector, weak_area, coverage, weak_grid, report]"),
                    "拒绝错误应列出 skill 要求的精确 id: " + r.error());
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void planCreateIdempotentResponseEmbedsNextActionHint() throws Exception {
        seedPlan();
        ToolResult second = planCreateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create",
                        objectMapper.writeValueAsString(Map.of(
                                "title", "coverage",
                                "steps", List.of(
                                        Map.of("id", "sector", "title", "扇区", "toolHint", "db.query"),
                                        Map.of("id", "weak", "title", "弱覆盖", "toolHint", "db.query"),
                                        Map.of("id", "report", "title", "报告", "toolHint", "artifact.markdown")
                                )
                        ))));
        assertTrue(second.ok(), "second plan.create must be idempotent");
        JsonNode data = objectMapper.readTree(second.dataJson());
        String next = data.path("nextAction").asText("");
        assertFalse(next.isBlank(), "nextAction should be populated so LLM knows what to do");
        assertTrue(next.contains("sector"),
                "nextAction should point at the first pending step. actual=" + next);
        assertTrue(next.contains("plan.update"),
                "nextAction should name plan.update as the next tool. actual=" + next);
        assertTrue(next.contains("IN_PROGRESS"),
                "nextAction should instruct to move PENDING → IN_PROGRESS first. actual=" + next);
        assertTrue(second.summary().contains("sector"),
                "summary should surface the next step too — summary=" + second.summary());
    }

    @Test
    void planUpdateRejectionEmbedsNextActionHint() throws Exception {
        // 模拟本 bug 场景：LLM 在没跑 db.query 之前直接把 sector 跳到 COMPLETED
        seedPlan();
        // 没有 SkillGuard 时 PlanStepRuleEvaluator 不会跑 —— 这里手动注册一个 sector 规则
        // 要求至少一次 db.query 成功，模拟线上的 skill.coverage.analysis 效果
        PlanStepRule sectorRule = new PlanStepRule(
                List.of("db.query"),   // requiresSuccess
                List.of(),              // tableAllowList
                1,                      // minQueries
                true,                   // zeroRowsAllowed
                List.of(),              // mustMatchTemplateAnchors
                "",                     // reuseStep
                List.of(),              // sqlTemplates
                List.of(),              // sqlTemplatesGeoJson
                List.of(),              // sqlTemplatesNoFilter
                null,                   // reportSection
                "",                     // jdbcUrl
                List.of(),              // requiredTables
                List.of(),              // dependsOn
                false,                  // dependsOnDeclared
                PlanStepRule.Acceptance.NONE
        );
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("sector", "weak", "report"),
                Map.of("sector", sectorRule),
                List.of("skill.unit.test")
        );
        skillGuardStore.register(runId, guard);
        try {
            String args = objectMapper.writeValueAsString(Map.of(
                    "stepId", "sector",
                    "status", "COMPLETED",
                    "resultNote", "fabricated numbers"
            ));
            ToolResult r = planUpdateTool.execute(
                    new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.update", args));
            assertFalse(r.ok(), "plan.update COMPLETED without db.query must be rejected");
            String error = r.error();
            assertNotNull(error);
            assertTrue(error.contains("Next action"),
                    "rejection should contain actionable hint. actual=" + error);
            assertTrue(error.contains("IN_PROGRESS"),
                    "hint should tell LLM to set IN_PROGRESS first. actual=" + error);
            assertTrue(error.contains("db.query"),
                    "hint should name db.query as the work tool. actual=" + error);
            assertTrue(error.contains("\"stepId\":\"sector\""),
                    "hint should include ready-to-paste JSON args. actual=" + error);
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void planUpdateBlocksArtifactStepCompletionWithoutRealArtifact() throws Exception {
        seedPlan();
        // 默认串行 dep 兜底要求 sector → weak → report;此测试只关心 artifact 完成性校验,
        // 用 fixture 直推先把前两步标 COMPLETED 解锁 report。
        forceCompleted("sector", "weak");
        // 先把 report 标为 IN_PROGRESS（合法，不需要 artifact）
        planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of("stepId", "report", "status", "IN_PROGRESS"))));

        // 直接尝试 COMPLETED —— 未调 artifact.markdown，应被后端拒绝
        ToolResult r = planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of(
                        "stepId", "report",
                        "status", "COMPLETED",
                        "resultNote", "伪造的完成"))));
        assertFalse(r.ok(), "artifact step cannot COMPLETE without real artifact tool call");
        assertNotNull(r.error());
        assertTrue(r.error().contains("artifact.*") || r.error().contains("artifact."),
                "error should mention the missing artifact.* invocation");
        assertEquals(PlanStatus.IN_PROGRESS,
                runPlanStore.find(runId).orElseThrow().find("report").orElseThrow().status(),
                "step status must stay IN_PROGRESS when completion is rejected");

        // 模拟 artifact.markdown 成功调用挂到 step
        PlanStep reportStep = runPlanStore.find(runId).orElseThrow().find("report").orElseThrow();
        reportStep.attachToolSummary(CompletedToolSummary.of(
                "artifact.markdown", "artifact.markdown:md:abc",
                "Generated markdown document coverage.md", 1, "report"));

        // 现在再 COMPLETED 应该通过
        ToolResult ok = planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of(
                        "stepId", "report",
                        "status", "COMPLETED",
                        "resultNote", "报告已生成"))));
        assertTrue(ok.ok(), "completion must pass once artifact summary is present: " + ok.error());
        assertEquals(PlanStatus.COMPLETED,
                runPlanStore.find(runId).orElseThrow().find("report").orElseThrow().status());
    }

    @Test
    void planUpdateAdvancesStepStatus() throws Exception {
        seedPlan();
        String updatePayload = objectMapper.writeValueAsString(Map.of(
                "stepId", "sector",
                "status", "IN_PROGRESS"
        ));
        ToolResult r = planUpdateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.update", updatePayload));
        assertTrue(r.ok(), r.error());
        assertEquals(PlanStatus.IN_PROGRESS, runPlanStore.find(runId).orElseThrow().find("sector").orElseThrow().status());

        String completePayload = objectMapper.writeValueAsString(Map.of(
                "stepId", "sector",
                "status", "COMPLETED",
                "resultNote", "2G=120, 4G=856, 5G=412"
        ));
        planUpdateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.update", completePayload));
        PlanStep step = runPlanStore.find(runId).orElseThrow().find("sector").orElseThrow();
        assertEquals(PlanStatus.COMPLETED, step.status());
        assertTrue(step.resultNote().contains("5G=412"));
    }

    @Test
    void planUpdateRejectsUnknownStepOrStatus() throws Exception {
        seedPlan();
        ToolResult unknownStep = planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of("stepId", "nope", "status", "COMPLETED"))));
        assertFalse(unknownStep.ok());

        ToolResult badStatus = planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of("stepId", "sector", "status", "BOGUS"))));
        assertFalse(badStatus.ok());
    }

    @Test
    void requiredTablesRejectsCompletionUntilEveryTableIsQueried() throws Exception {
        // 再现线上 bug：weak_area step 要求查 OTT 5G + OTT 4G + MDT 4G 三张表，
        // 但 LLM 只查了 OTT 5G 一张就想 COMPLETED，剩下的"靠记忆填"。
        // requiredTables 规则必须挡住：三张表都跑过 db.query 才能通过。
        seedPlan();
        PlanStepRule weakAreaRule = new PlanStepRule(
                List.of("db.query"), List.of(), 1, true, List.of(), "",
                List.of(), List.of(), List.of(), null, "",
                List.of(
                        "xmap_ott.layer_yunnan_5g_weak_coverage_region",
                        "xmap_ott.layer_yunnan_4g_weak_coverage_region",
                        "xmap.layer_mdt_grid_nokia_50_month_weakarea"
                ),
                List.of(),
                false,
                PlanStepRule.Acceptance.NONE
        );
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(), java.util.Set.of(),
                List.of("sector", "weak", "report"),
                Map.of("weak", weakAreaRule),
                List.of("skill.unit.test")
        );
        skillGuardStore.register(runId, guard);
        try {
            // 默认串行 dep 兜底:weak 依赖 sector,先 fixture 推进 sector 解锁 weak
            forceCompleted("sector");
            markInProgress("weak");
            // 只跑 OTT 5G 一张表 —— 不够
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) FROM xmap_ott.layer_yunnan_5g_weak_coverage_region WHERE 1=1",
                    "[]", "1 rows", 1));

            String args = objectMapper.writeValueAsString(Map.of(
                    "stepId", "weak",
                    "status", "COMPLETED",
                    "resultNote", "OTT 5G 查完；4G/MDT 从记忆中读"
            ));
            ToolResult rejected = planUpdateTool.execute(new ToolInvocation(
                    "dev-agent", runId, "sess", "junit", "plan.update", args));
            assertFalse(rejected.ok(),
                    "只跑了 1 张表就想 COMPLETED 必须被 requiredTables 规则拒绝");
            assertTrue(rejected.error().contains("xmap_ott.layer_yunnan_4g_weak_coverage_region"),
                    "错误消息应列出缺少哪张表: " + rejected.error());
            assertTrue(rejected.error().contains("xmap.layer_mdt_grid_nokia_50_month_weakarea"),
                    "错误消息应列出 MDT 表: " + rejected.error());

            // 再跑另外两张表
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) FROM xmap_ott.layer_yunnan_4g_weak_coverage_region WHERE 1=1",
                    "[]", "1 rows", 1));
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) FROM xmap.layer_mdt_grid_nokia_50_month_weakarea WHERE 1=1",
                    "[]", "1 rows", 1));

            ToolResult passed = planUpdateTool.execute(new ToolInvocation(
                    "dev-agent", runId, "sess", "junit", "plan.update",
                    objectMapper.writeValueAsString(Map.of(
                            "stepId", "weak",
                            "status", "COMPLETED",
                            "resultNote", "三张表都查完"))));
            assertTrue(passed.ok(),
                    "三张 required table 都跑过 db.query 后应可通过: " + passed.error());
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void acceptanceRequireNonZeroDataRejectsAllZeroAggregateResults() throws Exception {
        // 再现 bug:LLM 跑了一组 COUNT(*) 全部返回 0(filter 错或 region 真的空),
        // 之前直接 plan.update COMPLETED + artifact.markdown 把 0 写进报告。
        // acceptance.requireNonZeroData=true 必须挡住这一路。
        seedPlan();
        PlanStepRule sectorRule = new PlanStepRule(
                List.of("db.query"), List.of(), 1, true, List.of(), "",
                List.of(), List.of(), List.of(), null, "",
                List.of(),
                List.of(), false,
                new PlanStepRule.Acceptance(
                        List.of("cnt_2g", "cnt_4g", "cnt_5g"),
                        true
                )
        );
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(), java.util.Set.of(),
                List.of("sector", "weak", "report"),
                Map.of("sector", sectorRule),
                List.of("skill.unit.test")
        );
        skillGuardStore.register(runId, guard);
        try {
            markInProgress("sector");
            // 三条 SQL 全部返回 cnt=0,模拟"region 全空"现象
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) AS cnt_2g FROM xmap.layer_wy_2g_cell_info_section_yn WHERE 1=1",
                    "[{\"cnt_2g\":0}]", "1 rows", 1));
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) AS cnt_4g FROM xmap.layer_wy_4g_cell_info_section_yn WHERE 1=1",
                    "[{\"cnt_4g\":0}]", "1 rows", 1));
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) AS cnt_5g FROM xmap.layer_wy_5g_cell_info_section_yn WHERE 1=1",
                    "[{\"cnt_5g\":0}]", "1 rows", 1));

            ToolResult rejected = planUpdateTool.execute(new ToolInvocation(
                    "dev-agent", runId, "sess", "junit", "plan.update",
                    objectMapper.writeValueAsString(Map.of(
                            "stepId", "sector",
                            "status", "COMPLETED",
                            "resultNote", "全 0,但我还是想结束这一步"))));
            assertFalse(rejected.ok(),
                    "全 0 聚合结果在 requireNonZeroData=true 下必须被拒,LLM 才会重查或 SKIPPED");
            assertTrue(rejected.error().contains("all numeric values")
                            || rejected.error().contains("zero"),
                    "错误消息应说明所有数值都为 0: " + rejected.error());

            // 重跑一条带非零结果,完成应该通过
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) AS cnt_5g FROM xmap.layer_wy_5g_cell_info_section_yn WHERE city = '昆明市'",
                    "[{\"cnt_5g\":412}]", "1 rows", 1));
            ToolResult passed = planUpdateTool.execute(new ToolInvocation(
                    "dev-agent", runId, "sess", "junit", "plan.update",
                    objectMapper.writeValueAsString(Map.of(
                            "stepId", "sector",
                            "status", "COMPLETED",
                            "resultNote", "重查后 cnt_5g=412,有数据"))));
            assertTrue(passed.ok(),
                    "重查到非零数据后 acceptance 应通过: " + passed.error());
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void acceptanceRequiredColumnsRejectsWhenSelectListMissesField() throws Exception {
        // requiredColumns 校验 LLM 是否查了正确的 SELECT 列。如果只 SELECT count(*) 但
        // skill 要求字段名 cnt_2g/cnt_4g/cnt_5g,evaluator 应当指出缺哪些列。
        seedPlan();
        PlanStepRule sectorRule = new PlanStepRule(
                List.of("db.query"), List.of(), 1, true, List.of(), "",
                List.of(), List.of(), List.of(), null, "",
                List.of(),
                List.of(), false,
                new PlanStepRule.Acceptance(
                        List.of("cnt_2g", "cnt_4g", "cnt_5g"),
                        false
                )
        );
        SkillGuard guard = new SkillGuard(
                java.util.Set.of(), java.util.Set.of(),
                List.of("sector", "weak", "report"),
                Map.of("sector", sectorRule),
                List.of("skill.unit.test")
        );
        skillGuardStore.register(runId, guard);
        try {
            markInProgress("sector");
            // 错的 SELECT 列名:不是 cnt_2g,而是 count
            toolResultAppender.append(context, successfulQueryOutcome(
                    "SELECT COUNT(*) AS count FROM xmap.layer_wy_2g_cell_info_section_yn",
                    "[{\"count\":120}]", "1 rows", 1));
            ToolResult rejected = planUpdateTool.execute(new ToolInvocation(
                    "dev-agent", runId, "sess", "junit", "plan.update",
                    objectMapper.writeValueAsString(Map.of(
                            "stepId", "sector",
                            "status", "COMPLETED",
                            "resultNote", "用 count 字段了"))));
            assertFalse(rejected.ok(),
                    "SELECT 列名不匹配 requiredColumns 必须被拒");
            assertTrue(rejected.error().contains("cnt_2g")
                            || rejected.error().contains("required columns"),
                    "错误消息应指出缺哪些列: " + rejected.error());
        } finally {
            skillGuardStore.unregister(runId);
        }
    }

    @Test
    void artifactSuccessAutoAdvancesMatchingStepEvenIfStillPending() throws Exception {
        // 再现线上 bug：LLM 没先调 plan.update report=IN_PROGRESS 就直接调 artifact.markdown。
        // 之前此场景下 report step 永远卡在 PENDING，run-end gate 一直拦着 LLM 不让结束。
        // 修复后：artifact.markdown 成功时后端兜底把 report 推到 COMPLETED。
        seedPlan();
        // 注意：故意不调 markInProgress(report)。report 现在是 PENDING。

        ToolCallOutcome artifactOutcome = successfulArtifactOutcome(
                "artifact.markdown",
                "Generated markdown document coverage.md",
                "# 覆盖分析报告\n\n## 1. 区域扇区概况\n..."
        );
        toolResultAppender.append(context, artifactOutcome);

        RunPlan plan = runPlanStore.find(runId).orElseThrow();
        PlanStep report = plan.find("report").orElseThrow();
        assertEquals(PlanStatus.COMPLETED, report.status(),
                "artifact.markdown 成功后 report 必须自动推进到 COMPLETED，而不是卡在 PENDING");
        assertEquals(1, report.toolSummaries().size(),
                "auto-advance 路径必须把 summary attach 到 step 上，否则 PlanStepRuleEvaluator 过不了");
        assertNotNull(report.resultNote());
        assertTrue(report.resultNote().contains("auto-advanced"),
                "resultNote 应标注是后端自动推进的，便于排查: " + report.resultNote());
    }

    @Test
    void completedSummaryAttachesToInProgressStepAndAppearsInPrompt() throws Exception {
        seedPlan();
        markInProgress("sector");

        // 模拟一次 db.query 成功
        ToolCallOutcome outcome = successfulQueryOutcome(
                "SELECT fre_band AS frequency_band, COUNT(*) AS sector_count "
                        + "FROM xmap.layer_wy_5g_cell_info_section_yn WHERE 1=1 GROUP BY fre_band",
                "[{\"fre_band\":\"3.5GHz\",\"sector_count\":412}]",
                "Query returned 1 row", 1);
        toolResultAppender.append(context, outcome);

        RunPlan plan = runPlanStore.find(runId).orElseThrow();
        PlanStep sector = plan.find("sector").orElseThrow();
        assertEquals(1, sector.toolSummaries().size(), "summary must attach to the active step");
        assertEquals(1, context.completedToolSummaries().size());

        String prompt = context.currentPrompt();
        assertTrue(prompt.contains("[Plan]"), "currentPrompt must include Plan section");
        assertTrue(prompt.contains("[Completed Queries]"), "currentPrompt must include Completed Queries section");
        assertTrue(prompt.contains("rows=1"), "summary must show row count");
        assertTrue(prompt.contains("step=sector"), "summary must carry step id");
    }

    @Test
    void semanticallyEqualSqlShareSameFingerprint() throws Exception {
        seedPlan();
        markInProgress("sector");

        ToolCallOutcome first = successfulQueryOutcome(
                "SELECT fre_band AS frequency_band, COUNT(*) AS cell_count "
                        + "FROM xmap.layer_wy_5g_cell_info_section_yn GROUP BY fre_band ORDER BY cell_count DESC",
                "[]", "3 rows", 3);
        ToolCallOutcome second = successfulQueryOutcome(
                "SELECT fre_band, COUNT(*) AS sector_count "
                        + "FROM xmap.layer_wy_5g_cell_info_section_yn GROUP BY fre_band ORDER BY sector_count DESC",
                "[]", "3 rows", 3);

        toolResultAppender.append(context, first);
        toolResultAppender.append(context, second);

        List<CompletedToolSummary> summaries = context.completedToolSummaries();
        assertEquals(2, summaries.size());
        assertEquals(summaries.get(0).argumentsFingerprint(), summaries.get(1).argumentsFingerprint(),
                "列别名改动必须归一化到同一指纹");
    }

    private void seedPlan() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "title", "coverage",
                "steps", List.of(
                        Map.of("id", "sector", "title", "扇区", "toolHint", "db.query", "expectedOutput", ""),
                        Map.of("id", "weak", "title", "弱覆盖", "toolHint", "db.query", "expectedOutput", ""),
                        Map.of("id", "report", "title", "报告", "toolHint", "artifact.markdown", "expectedOutput", "")
                )
        ));
        ToolResult r = planCreateTool.execute(
                new ToolInvocation("dev-agent", runId, "sess", "junit", "plan.create", payload));
        assertTrue(r.ok(), r.error());
    }

    private void markInProgress(String stepId) throws Exception {
        ToolResult r = planUpdateTool.execute(new ToolInvocation(
                "dev-agent", runId, "sess", "junit", "plan.update",
                objectMapper.writeValueAsString(Map.of("stepId", stepId, "status", "IN_PROGRESS"))));
        assertTrue(r.ok(), r.error());
    }

    /**
     * 测试 fixture 直推:绕过 plan.update 的 dependsOn barrier 强制把前置 step 标为 COMPLETED,
     * 这样后续测试逻辑能直接 markInProgress 后面的 step。仅在 setUp 用,真实调用路径必须走
     * plan.update。
     */
    private void forceCompleted(String... stepIds) {
        for (String id : stepIds) {
            runPlanStore.find(runId).orElseThrow()
                    .find(id).orElseThrow()
                    .updateStatus(PlanStatus.COMPLETED);
        }
    }

    private ToolCallOutcome successfulArtifactOutcome(String toolName, String summary, String markdown) throws Exception {
        String argsJson = objectMapper.writeValueAsString(Map.of(
                "name", "coverage.md",
                "content", markdown
        ));
        ToolCallRequest request = new ToolCallRequest(
                UUID.randomUUID().toString(),
                toolName,
                argsJson,
                ""
        );
        ToolCallDecision decision = new ToolCallDecision(true, false, "allowed", toolName, argsJson);
        String dataJson = objectMapper.writeValueAsString(Map.of(
                "displayType", "markdown",
                "artifactId", "test-artifact-1",
                "name", "coverage.md",
                "contentType", "text/markdown; charset=UTF-8",
                "markdown", markdown
        ));
        ToolResult result = new ToolResult(true, summary, dataJson, "[]", null);
        return new ToolCallOutcome(request, decision, true, true, result, null, 0L);
    }

    private ToolCallOutcome successfulQueryOutcome(String sql, String rowsJson, String summary, int rowCount) throws Exception {
        String argsJson = objectMapper.writeValueAsString(Map.of(
                "sql", sql,
                "jdbcUrl", "jdbc:postgresql://mock/db"
        ));
        ToolCallRequest request = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "db.query",
                argsJson,
                ""
        );
        ToolCallDecision decision = new ToolCallDecision(
                true, false, "allowed", "db.query", argsJson);
        String dataJson = objectMapper.writeValueAsString(Map.of(
                "displayType", "table",
                "rows", objectMapper.readTree(rowsJson)
        ));
        ToolResult result = new ToolResult(true, summary, dataJson, "[]", null);
        return new ToolCallOutcome(request, decision, true, true, result, null, 0L);
    }
}
