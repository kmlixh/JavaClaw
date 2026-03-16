package com.janyee.agent.runtime.admin;

public record SkillDefinitionCommand(
        String id,
        String agentId,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        boolean enabled
) {
}
