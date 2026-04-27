package com.janyee.agent.infra.skill;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定线上观察的 bug：用户选 ops-agent 发"盘龙区覆盖分析"，skill.coverage.analysis 只绑给 dev-agent，
 * LLM 在 ops-agent 里没有 plan/whitelist/prompt 指导，跑 30 轮 file.list 毫无收获。
 *
 * 修复后：检测到关键词匹配但当前 agent 未持有对应 skill → 立刻返回带引导的错误。
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
class SkillAgentMismatchCheckerTest {

    @Autowired
    private SkillAgentMismatchChecker checker;

    @Autowired
    private SkillDefinitionRepository repository;

    @Test
    void messageMatchingCoverageTriggerReportsOpsAgentMismatch() {
        // V18 已给 skill.coverage.analysis 种了 trigger_keywords（含 "覆盖分析"）
        Optional<SkillAgentMismatchChecker.MismatchResult> mismatch =
                checker.detect("ops-agent", "给出盘龙区的覆盖分析综合报告");
        assertTrue(mismatch.isPresent(),
                "ops-agent 不挂 skill.coverage.analysis，关键词命中后应报 mismatch");
        SkillAgentMismatchChecker.MismatchResult r = mismatch.get();
        assertNotNull(r.skillName());
        assertTrue(r.ownerAgentIds().contains("dev-agent"),
                "ownerAgentIds 应列出真正绑定此 skill 的 agent: " + r.ownerAgentIds());
        assertTrue(r.matchedKeyword().contains("覆盖"),
                "matchedKeyword 应告诉用户是哪个词触发的: " + r.matchedKeyword());
        String friendly = r.friendlyMessage("ops-agent");
        assertTrue(friendly.contains("ops-agent") && friendly.contains(r.skillName()),
                "friendlyMessage 应包含当前 agent 和目标 skill 名: " + friendly);
    }

    @Test
    void matchingAgentPassesSilently() {
        // dev-agent 自己有 skill.coverage.analysis → 不应报 mismatch
        Optional<SkillAgentMismatchChecker.MismatchResult> mismatch =
                checker.detect("dev-agent", "给出盘龙区的覆盖分析综合报告");
        assertFalse(mismatch.isPresent(),
                "当前 agent 已持有匹配 skill，不应触发 mismatch");
    }

    @Test
    void messageWithNoSkillTriggerPassesSilently() {
        Optional<SkillAgentMismatchChecker.MismatchResult> mismatch =
                checker.detect("ops-agent", "你好，今天天气怎么样？");
        assertFalse(mismatch.isPresent(),
                "与任何 skill trigger 都无关的普通消息不应触发 mismatch");
    }

    @Test
    void blankMessageIsHandledGracefully() {
        assertFalse(checker.detect("dev-agent", null).isPresent());
        assertFalse(checker.detect("dev-agent", "").isPresent());
        assertFalse(checker.detect("dev-agent", "   ").isPresent());
    }
}
