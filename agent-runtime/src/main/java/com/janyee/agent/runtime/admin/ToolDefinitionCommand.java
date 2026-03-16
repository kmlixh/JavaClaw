package com.janyee.agent.runtime.admin;

public record ToolDefinitionCommand(
        String id,
        String agentId,
        String toolName,
        String displayName,
        String description,
        String schemaJson,
        String toolType,
        String configJson,
        boolean enabled,
        boolean approvalRequired
) {
}
