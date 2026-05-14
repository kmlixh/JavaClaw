package com.janyee.agent.runtime.lab;

/**
 * 改写约束规则后再次迭代的请求体。POST /api/lab/tasks/{id}/refine。
 * 跟 restart 不同:这里允许用户在 v1 调试结果出来后,加几条 / 删几条规则,
 * 再让 AI 重新派生测试场景 + 从 round-1 调试。
 */
public record AgentLabRefineRequest(
        // —— 内容字段:全部 patch 语义 —— null = 不动这一项;空串 = 显式清空(referenceDocuments 用);
        // 非 null 非空 = 整段覆盖
        String title,
        String goalDescription,
        Integer maxIterations,         // null 不动,非 null 即覆盖(范围由 service 层夹紧)
        String constraintRules,
        String referenceDocuments,
        // —— LLM 双栏覆盖
        String metaLlmConfigId,
        String metaLlmModel,
        String testLlmConfigId,
        String testLlmModel
) {
}
