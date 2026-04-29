package com.janyee.agent.api;

import java.time.Instant;

public record ToolDefinitionResponse(
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
        int version,
        Instant createdAt,
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
