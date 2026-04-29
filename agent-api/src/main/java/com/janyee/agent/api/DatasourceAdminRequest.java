package com.janyee.agent.api;

public record DatasourceAdminRequest(
        String id,
        String displayName,
        String jdbcUrl,
        String username,
        String password,
        String dialect,
        String description,
        boolean enabled,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
