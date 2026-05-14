package com.janyee.agent.runtime.lab;

import java.time.Instant;

/** Task 列表 / 概览 DTO,不带 iterations 详情(细节走 detail 接口)。 */
public record AgentLabTaskView(
        String id,
        String ownerUserId,
        String title,
        String goalDescription,
        String testCasesJson,            // AI 派生的场景(老任务里是用户手填)
        String constraintRules,          // 用户自然语言写的约束规则(V38 起新增)
        String referenceDocuments,       // 用户粘贴的参考文档,可选
        int maxIterations,
        int currentIteration,
        String status,
        String mode,
        String targetType,
        String targetAgentId,
        String targetSkillName,
        String newSkillName,
        String cloneFromSkillName,
        String targetScopeType,
        String targetScopeTenantId,
        String cloneFromAgentId,
        String sandboxSkillNamesJson,
        String metaLlmConfigId,
        String testLlmConfigId,
        String llmConfigId,
        boolean allowAgentEvolution,
        String finalSummary,
        String errorDetail,
        Instant createdAt,
        Instant updatedAt
) {
}
