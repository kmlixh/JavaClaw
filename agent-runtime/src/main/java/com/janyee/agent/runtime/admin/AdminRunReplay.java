package com.janyee.agent.runtime.admin;

import java.time.Instant;

/**
 * 单个 run 的复盘数据。eventLogJson 是 JSON 数组(由 RunEventCollector 记录),
 * 前端解析后渲染成时间线 / 流程图。
 */
public record AdminRunReplay(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String tenantId,
        String appId,
        String llmConfigId,
        String llmModel,
        String status,
        String detail,
        String requestMessage,
        String planJson,
        String eventLogJson,
        Instant createdAt,
        Instant updatedAt
) {
}
