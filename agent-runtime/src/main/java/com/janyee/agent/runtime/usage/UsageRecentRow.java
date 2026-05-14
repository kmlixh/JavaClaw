package com.janyee.agent.runtime.usage;

import java.time.Instant;

/**
 * 最近 N 条 assistant 消息 + 关联 session/run 元数据,给"按时间倒序看每次消耗"的列表用。
 * sessionTitle 可能为 null(老 session 没标题就用 sessionId 显示)。
 */
public record UsageRecentRow(
        long messageId,
        String sessionId,
        String sessionTitle,
        String runId,
        String agentId,
        String tenantId,
        String appId,
        String userId,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Instant createdAt
) {
}
