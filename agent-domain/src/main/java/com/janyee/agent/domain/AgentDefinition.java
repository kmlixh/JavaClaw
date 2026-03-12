package com.janyee.agent.domain;

public record AgentDefinition(
        String id,
        String displayName,
        String systemPrompt,
        String workspacePath
) {
}
