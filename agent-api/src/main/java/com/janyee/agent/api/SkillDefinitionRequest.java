package com.janyee.agent.api;

public record SkillDefinitionRequest(
        String id,
        String agentId,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        boolean enabled
) {
}
