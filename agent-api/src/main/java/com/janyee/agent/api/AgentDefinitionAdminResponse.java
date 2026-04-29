package com.janyee.agent.api;

import java.time.Instant;

public record AgentDefinitionAdminResponse(
        String agentId,
        String displayName,
        String description,
        String systemPrompt,
        String agentMarkdown,
        String memoryMarkdown,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
