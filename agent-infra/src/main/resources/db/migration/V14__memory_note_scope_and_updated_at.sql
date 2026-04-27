-- Phase 1 of Plan A: make memory_note first-class — add scope to disambiguate session-only
-- vs agent-global vs cross-agent notes, and record updated_at so edits can be tracked.
--
-- Design: retrieval logic (WorkspaceMemoryService) picks notes by scope + session match:
--   scope='session'  → visible only within its own session_id
--   scope='agent'    → visible to every session of the same agent
--   scope='global'   → visible to every session of every agent (rarely used)
-- Historical run_summary notes default to session scope so they never leak across sessions
-- again (cf. the 'report contains other session's numbers' bug).

ALTER TABLE memory_note
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'session';

ALTER TABLE memory_note
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

-- Existing rows: run_summary keeps session scope, anything else manually saved is agent scope.
UPDATE memory_note SET scope = 'session' WHERE source = 'run_summary' AND scope = 'session';
UPDATE memory_note SET scope = 'agent'   WHERE source <> 'run_summary' AND scope = 'session';
UPDATE memory_note SET updated_at = created_at WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_memory_note_agent_scope     ON memory_note (agent_id, scope, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_note_agent_session   ON memory_note (agent_id, session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_note_agent_source    ON memory_note (agent_id, source, created_at DESC);
