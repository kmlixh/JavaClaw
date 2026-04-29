package com.janyee.agent.api;

public record ToolDefinitionRequest(
        String id,
        String agentId,
        String toolName,
        String displayName,
        String description,
        String schemaJson,
        String toolType,
        String configJson,
        boolean enabled,
        boolean approvalRequired,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
