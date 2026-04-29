package com.janyee.agent.api;

import java.time.Instant;

public record DatasourceAdminResponse(
        String id,
        String displayName,
        String jdbcUrl,
        String username,
        boolean passwordSet,
        String dialect,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
