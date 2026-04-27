-- Phase 2 of Plan A: move agent definitions out of the workspace/ filesystem into the DB.
-- Before this migration, listing agents = listing workspaces/* directories; display name
-- comes from the first H1 of AGENT.md; system prompt is SOUL.md or AGENT.md — changes
-- require shell access to the server. After this migration the agent_definition table is
-- the sole source of truth, the frontend can CRUD it, and workspaces/<agent>/ is retained
-- only for runtime artifacts + knowledge files.

CREATE TABLE IF NOT EXISTS agent_definition (
    agent_id         VARCHAR(128) PRIMARY KEY,
    display_name     VARCHAR(255) NOT NULL,
    description      TEXT,
    -- Equivalent of SOUL.md: system-level prompt fed to the LLM as role=system.
    system_prompt    TEXT NOT NULL DEFAULT '',
    -- Equivalent of AGENT.md: human-readable agent documentation, also injected into prompts
    -- as the AGENT.md: section so the LLM can read "who it is" and "what it does".
    agent_markdown   TEXT NOT NULL DEFAULT '',
    -- Equivalent of MEMORY.md: long-term scratchpad. Now that memory_note has scope=agent,
    -- this is optional — only kept as a legacy bucket for unstructured notes.
    memory_markdown  TEXT NOT NULL DEFAULT '',
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_definition_enabled
    ON agent_definition (enabled, updated_at DESC);
