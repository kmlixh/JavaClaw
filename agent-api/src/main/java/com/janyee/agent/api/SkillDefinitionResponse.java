package com.janyee.agent.api;

import java.time.Instant;

public record SkillDefinitionResponse(
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
