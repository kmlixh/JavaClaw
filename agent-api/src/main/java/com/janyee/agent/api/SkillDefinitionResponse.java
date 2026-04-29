package com.janyee.agent.api;

import java.time.Instant;
import java.util.List;

/**
 * @param agentId  legacy,保留给还在读这字段的老 UI。M:N 后请读 {@link #agentIds()}。
 * @param agentIds M:N 关系查出来的所有绑定 agent。前端多选 chips 就读这个。
 */
public record SkillDefinitionResponse(
        String id,
        String agentId,
        List<String> agentIds,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        String triggerKeywords,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
