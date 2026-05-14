package com.janyee.agent.runtime.admin;

import java.time.Instant;

/** 管理员会话列表的一行,带聚合统计字段。 */
public record AdminSessionRow(
        String sessionId,
        String title,
        String agentId,
        String userId,
        String tenantId,
        String appId,
        String channel,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long messageCount,
        long runCount,
        long totalTokens
) {
}
