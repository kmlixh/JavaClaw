package com.janyee.agent.runtime.skill;

public record SkillPrompt(
        String skillName,
        String description,
        String promptTemplate
) {
}
