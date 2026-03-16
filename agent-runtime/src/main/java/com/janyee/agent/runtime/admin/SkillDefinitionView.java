package com.janyee.agent.runtime.admin;

import java.time.Instant;

public record SkillDefinitionView(
        String id,
        String agentId,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
