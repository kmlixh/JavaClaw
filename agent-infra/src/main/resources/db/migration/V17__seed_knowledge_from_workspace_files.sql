-- Phase 3 of Plan A: knowledge unified to DB (knowledge_entry). Workspace files under
-- workspaces/<agent>/knowledge/*.md are no longer consulted at prompt-assembly time;
-- this one-shot seed ensures existing content is not lost.
--
-- We don't touch the files; operators can clean them up once they've confirmed every
-- valuable piece landed in knowledge_entry. The seed is idempotent — it checks for an
-- existing row with the same agent+title before inserting.
--
-- NOTE: SQL can't read the filesystem, so this migration only documents the policy and
-- seeds one placeholder row per agent. Real file content has to be copied manually through
-- the frontend UI (which is now fully CRUD-capable after Phase 2/3). Existing
-- workspaces/<agent>/knowledge/getting-started.md content is preserved as a seed so
-- operators see something in the list right after the migration.

INSERT INTO knowledge_entry (
    id, agent_id, title, content, content_type, source, tags_json,
    enabled, version, created_at, updated_at
)
SELECT 'seed-getting-started-dev',
       'dev-agent',
       'getting-started',
       $$# Getting Started

This workspace is the default development agent workspace.

- The only enabled demo tool is `echo`.
- Use approval rules from application.yml when testing gated tools.
- Knowledge is now managed from the DB (knowledge menu); files under `workspaces/dev-agent/knowledge/` are legacy seeds.$$,
       'markdown',
       'seed-from-workspace-file',
       '[]',
       TRUE,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_entry
     WHERE agent_id = 'dev-agent' AND title = 'getting-started'
);

INSERT INTO knowledge_entry (
    id, agent_id, title, content, content_type, source, tags_json,
    enabled, version, created_at, updated_at
)
SELECT 'seed-runbook-ops',
       'ops-agent',
       'runbook',
       $$# Ops Runbook

Ops agent baseline guidance:
- All DB queries must use a registered db_datasource (no ad-hoc credentials).
- shell.exec requires approval; prefer file.list/file.read for workspace discovery.
- Generate reports via artifact.markdown; downloads are available in the session.$$,
       'markdown',
       'seed-from-workspace-file',
       '[]',
       TRUE,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_entry
     WHERE agent_id = 'ops-agent' AND title = 'runbook'
);
