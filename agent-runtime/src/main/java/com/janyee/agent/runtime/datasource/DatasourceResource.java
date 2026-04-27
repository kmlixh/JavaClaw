package com.janyee.agent.runtime.datasource;

/**
 * Value object describing a named backend datasource. The agent runtime uses this to
 * auto-inject credentials into tool calls so the LLM never sees or has to manage secrets.
 */
public record DatasourceResource(
        String id,
        String displayName,
        String jdbcUrl,
        String username,
        String password,
        String dialect
) {
}
