package com.janyee.agent.runtime.admin;

import java.time.Instant;

/** 一个 session 下的 run 概要,给"复盘前的选 run"列表用。 */
public record AdminRunSummary(
        String runId,
        String status,
        String detail,
        String llmConfigId,
        String llmModel,
        Instant createdAt,
        Instant updatedAt,
        boolean hasEventLog,
        Integer eventCount
) {
}
