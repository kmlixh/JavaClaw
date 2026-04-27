package com.janyee.agent.runtime.datasource;

import java.util.Optional;

/**
 * Resolves named datasource resources. Implementations typically back this with a JPA
 * repository so credentials live outside chat history, skill prompts, and tool-call JSON.
 */
public interface DatasourceResourceService {
    Optional<DatasourceResource> findByJdbcUrl(String jdbcUrl);
    Optional<DatasourceResource> findById(String id);

    /**
     * Default datasource for tool calls that omit jdbcUrl. Must be an enabled row from
     * {@code db_datasource} — never the system's own application datasource. If no enabled
     * row exists, returns empty and the caller decides how to fail. This is the guardrail
     * that keeps queries from accidentally running against the agent's internal Postgres.
     */
    Optional<DatasourceResource> findDefault();
}
