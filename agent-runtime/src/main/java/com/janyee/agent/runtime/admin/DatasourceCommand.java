package com.janyee.agent.runtime.admin;

/**
 * Upsert command for a named datasource. When {@code password} is null or empty the existing
 * row's password is kept (so "save display name" flows don't force the admin to re-enter it).
 */
public record DatasourceCommand(
        String id,
        String displayName,
        String jdbcUrl,
        String username,
        String password,
        String dialect,
        String description,
        boolean enabled
) {
}
