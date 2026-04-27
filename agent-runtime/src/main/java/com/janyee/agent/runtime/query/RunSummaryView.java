package com.janyee.agent.runtime.query;

import java.time.Instant;

/**
 * 每个会话的 run 状态快照，给"会话 run 列表"接口使用。不包含 tool/audit/artifact 明细，
 * 只保留前端判定"这个 run 是否还在执行"所需的信息。状态是经过 StaleRunReconciler 修正过的
 * 权威值，所以前端不需要再根据 event 流二次推断。
 */
public record RunSummaryView(
        String runId,
        String sessionId,
        String status,
        String detail,
        Instant createdAt,
        Instant updatedAt
) {
}
