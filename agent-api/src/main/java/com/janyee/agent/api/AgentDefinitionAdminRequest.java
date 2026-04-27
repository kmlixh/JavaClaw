package com.janyee.agent.api;

public record AgentDefinitionAdminRequest(
        String agentId,
        String displayName,
        String description,
        String systemPrompt,
        String agentMarkdown,
        String memoryMarkdown,
        Boolean enabled
) {
}
