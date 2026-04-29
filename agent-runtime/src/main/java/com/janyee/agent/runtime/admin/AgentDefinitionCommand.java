package com.janyee.agent.runtime.admin;

public record AgentDefinitionCommand(
        String agentId,
        String displayName,
        String description,
        String systemPrompt,
        String agentMarkdown,
        String memoryMarkdown,
        Boolean enabled,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
