package com.janyee.agent.domain;

public record AgentDefinition(
        String id,
        String displayName,
        String systemPrompt,
        String workspacePath,
        /**
         * Human-readable description of the agent's responsibilities, injected into the
         * assembled prompt as the AGENT.md: section. Historically read from
         * {@code workspaces/<agent>/AGENT.md}; after Plan A Phase 2 sourced from the
         * {@code agent_definition} table.
         */
        String agentMarkdown,
        /**
         * Agent-level long-term notes, injected as the MEMORY.md: prompt section. Historically
         * read from {@code workspaces/<agent>/MEMORY.md}; after Plan A Phase 2 sourced from the
         * {@code agent_definition} table.
         */
        String memoryMarkdown
) {
    /** Backward-compat constructor for call sites that only know id/name/prompt/workspace. */
    public AgentDefinition(String id, String displayName, String systemPrompt, String workspacePath) {
        this(id, displayName, systemPrompt, workspacePath, "", "");
    }
}
