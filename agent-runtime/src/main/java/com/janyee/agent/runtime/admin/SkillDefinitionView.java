package com.janyee.agent.runtime.admin;

import java.time.Instant;
import java.util.List;

public record SkillDefinitionView(
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
