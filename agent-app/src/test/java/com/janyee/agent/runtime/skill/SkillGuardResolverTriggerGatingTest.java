package com.janyee.agent.runtime.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现线上 bug：用户发"你是谁？"到 dev-agent，skill.coverage.analysis 仍被挂上 guard，
 * run-end gate 一直把 LLM 拦住不让结束，导致反复自述身份。
 *
 * <p>修复：skill 的 {@code triggerKeywords} 非空时，只在用户消息命中关键词才激活；否则
 * {@link SkillGuardResolver#resolve} 返回 {@link SkillGuard#NONE}。</p>
 */
class SkillGuardResolverTriggerGatingTest {

    private static final List<String> COVERAGE_TRIGGERS =
            List.of("覆盖分析", "覆盖率", "弱覆盖", "扇区统计", "盘龙区覆盖");

    @Test
    void coverageSkillDoesNotActivateForIdentityQuestion() {
        SkillConstraintService service = agentId -> List.of(coverageConstraint());
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        SkillGuard guard = resolver.resolve("dev-agent", "你是谁？");

        assertTrue(guard.isEmpty(),
                "非覆盖类消息不应激活 coverage skill，否则 run-end gate 会拦住 LLM");
        assertTrue(guard.requiredPlanStepIds().isEmpty());
        assertTrue(guard.whitelistTables().isEmpty());
    }

    @Test
    void coverageSkillActivatesOnKeywordMatch() {
        SkillConstraintService service = agentId -> List.of(coverageConstraint());
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        SkillGuard guard = resolver.resolve("dev-agent", "给出盘龙区的覆盖分析综合报告");

        assertFalse(guard.isEmpty(),
                "命中 trigger keyword 时必须激活 guard，这是 coverage skill 的正常工作路径");
        assertEquals(List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                guard.requiredPlanStepIds());
        assertTrue(guard.whitelistTables().contains("xmap.layer_wy_5g_cell_info_section_yn"));
    }

    @Test
    void skillWithoutTriggersStaysAlwaysActive() {
        // 没有 trigger_keywords 的 skill 保持 legacy 行为：对任何消息都激活，防止已有配置
        // 因升级而突然失去保护。
        SkillConstraint legacy = new SkillConstraint(
                "skill.legacy",
                List.of("public.whatever"),
                List.of("s1"),
                Map.of(),
                true,
                List.of()
        );
        SkillConstraintService service = agentId -> List.of(legacy);
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        SkillGuard guard = resolver.resolve("dev-agent", "你是谁？");
        assertFalse(guard.isEmpty(),
                "空 triggerKeywords = legacy always-active，保留向后兼容");
    }

    @Test
    void nullOrBlankMessageKeepsLegacyBehavior() {
        // resume 流程不带原消息 —— 这种情况必须回落到 always-active，否则 guard 会被意外丢掉。
        SkillConstraintService service = agentId -> List.of(coverageConstraint());
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        assertFalse(resolver.resolve("dev-agent", null).isEmpty(),
                "null 消息（resume 路径）必须保留 guard");
        assertFalse(resolver.resolve("dev-agent", "   ").isEmpty(),
                "空白消息也按 resume 处理");
    }

    @Test
    void caseInsensitiveMatch() {
        SkillConstraint enConstraint = new SkillConstraint(
                "skill.kpi.test",
                List.of("xmap.test"),
                List.of("s1"),
                Map.of(),
                true,
                List.of("Coverage")
        );
        SkillConstraintService service = agentId -> List.of(enConstraint);
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        assertFalse(resolver.resolve("dev-agent", "show me coverage").isEmpty());
        assertFalse(resolver.resolve("dev-agent", "SHOW COVERAGE REPORT").isEmpty());
        assertTrue(resolver.resolve("dev-agent", "hello world").isEmpty());
    }

    @Test
    void gatedOutSkillDoesNotRegisterWhitelistOrStepRules() {
        // 即使 skill 的 whitelistTables 和 planStepRules 都非空，trigger 不命中也不能贡献；
        // 否则"trigger-gating"只做了一半 —— LLM 依然会被 whitelist 挡住。
        SkillConstraintService service = agentId -> List.of(coverageConstraint());
        SkillGuardResolver resolver = new SkillGuardResolver(service);

        SkillGuard guard = resolver.resolve("dev-agent", "今天天气如何？");

        assertTrue(guard.whitelistTables().isEmpty(),
                "trigger 没命中时 whitelist 必须为空，否则 db.query 会被误拦");
        assertTrue(guard.stepRules().isEmpty(),
                "trigger 没命中时 stepRules 也不能残留");
    }

    private SkillConstraint coverageConstraint() {
        return new SkillConstraint(
                "skill.coverage.analysis",
                List.of(
                        "xmap.layer_wy_5g_cell_info_section_yn",
                        "xmap.layer_wy_4g_cell_info_section_yn",
                        "xmap_ott.layer_yunnan_5g_weak_coverage_region"
                ),
                List.of("sector", "weak_area", "coverage", "weak_grid", "report"),
                Map.of(),
                true,
                COVERAGE_TRIGGERS
        );
    }
}
