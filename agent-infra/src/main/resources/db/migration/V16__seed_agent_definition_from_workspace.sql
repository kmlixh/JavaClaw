-- Phase 2 of Plan A: one-time seed so existing deployments don't lose their agents when
-- the DB becomes the source of truth. If agent_definition is empty, insert sensible defaults
-- matching the historical workspaces/{dev,ops}-agent layout. After this migration the
-- operator can freely delete files from workspaces/* or keep them — the DB row is authoritative.
--
-- Real-world migration: operators with custom AGENT.md / SOUL.md content should run a
-- one-off script BEFORE this migration to copy their file contents into INSERT statements
-- and commit them as a V16.5 patch. We don't auto-read files here to keep Flyway reproducible
-- (Flyway can't access workspaces/ at runtime without JVM hooks).

INSERT INTO agent_definition (
    agent_id, display_name, description,
    system_prompt, agent_markdown, memory_markdown, enabled
)
SELECT 'dev-agent',
       'Dev Agent',
       '默认开发 agent：负责代码级分析、脚手架维护、工具/skill 编辑',
       $$You are the default development agent for this workspace.
Focus on building a pragmatic OpenClaw-like Java control plane.
Prefer incremental implementation, explicit state transitions, and auditable behavior.$$,
       $$# Dev Agent

This agent helps build and evolve the Java agent control plane project.

Primary responsibilities:
- analyze the current repository state
- implement code incrementally
- prefer minimal, testable changes$$,
       $$Stable project memory:
- The project uses Gradle multi-module layout.
- The current goal is a local-first OpenClaw-like Java agent platform.
- Build verification should be done with .\gradlew.bat build on Windows.$$,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE agent_id = 'dev-agent');

INSERT INTO agent_definition (
    agent_id, display_name, description,
    system_prompt, agent_markdown, memory_markdown, enabled
)
SELECT 'ops-agent',
       'Ops Agent',
       '运维/数据分析 agent：审批敏感命令、跑数据库查询、出报告',
       $$You are the ops agent. You help with operational queries, data analysis, and report generation.
Be cautious with shell commands — they require human approval.$$,
       $$# Ops Agent

This agent handles operational queries and data analysis tasks.

Responsibilities:
- run approved SQL against registered datasources
- generate reports and artifacts
- escalate shell commands for approval$$,
       $$Ops memory:
- This agent requires explicit skill bindings for structured analysis tasks.
- Without a matching skill, it falls back to ad-hoc exploration.$$,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE agent_id = 'ops-agent');
