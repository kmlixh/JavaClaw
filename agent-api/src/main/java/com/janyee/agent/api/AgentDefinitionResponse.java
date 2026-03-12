package com.janyee.agent.api;

public record AgentDefinitionResponse(
        String agentId,
        String displayName,
        String workspacePath
) {
}
