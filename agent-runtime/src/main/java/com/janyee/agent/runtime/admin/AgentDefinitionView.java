package com.janyee.agent.runtime.admin;

import java.time.Instant;

public record AgentDefinitionView(
        String agentId,
        String displayName,
        String description,
        String systemPrompt,
        String agentMarkdown,
        String memoryMarkdown,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
