package com.janyee.agent.runtime.admin;

import java.time.Instant;

/**
 * Admin-facing view of a named backend datasource. {@code password} is returned redacted
 * (never exposed in plaintext on list APIs) so the console can display a "Change password"
 * affordance without leaking existing secrets.
 */
public record DatasourceView(
        String id,
        String displayName,
        String jdbcUrl,
        String username,
        boolean passwordSet,
        String dialect,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
