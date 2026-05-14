package com.janyee.agent.runtime.query;

import java.time.Instant;

/**
 * 每个会话的 run 状态快照，给"会话 run 列表"接口使用。不包含 tool/audit/artifact 明细，
 * 只保留前端判定"这个 run 是否还在执行"所需的信息。状态直接来自 run_record 表，权威源:
 * executeRun 写终态 / RunStartupRecoveryService 重启时处理 / ScheduledRunReconcileSweeper 60min
 * hardStale 兜底；读路径不再做 reconcile 副作用。
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
