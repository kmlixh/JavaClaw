-- ============================================================================
-- P1 M2: 给所有"需要多租户隔离"的资源表加 scope 字段
-- ----------------------------------------------------------------------------
-- 四种 scope:
--   SYSTEM  - 系统级,全局可见。tenant_id = 'system',user_id = NULL
--   TENANT  - 某租户内可见。tenant_id = <tenant>, user_id = NULL
--   USER    - 某用户私有。tenant_id 可为 NULL(user 跨租户共享场景),user_id = <owner>
--   * 运行时查询 = WHERE scope_type='SYSTEM'
--                 OR (scope_type='TENANT' AND tenant_id=<current>)
--                 OR (scope_type='USER'   AND user_id=<current>)
--     → 用户自己的 skill/knowledge/memory/datasource 在任何租户里都可见(按需求 4)
--
-- 会话 / run 只有 tenant + app 两个字段;不需要 scope_type,因为会话天然属于发起的用户
-- (existing user_id column 保留,P2 里改为指向 app_user.id)。
-- ============================================================================

-- ---- session / run_record: 只加 tenant + app ---------------------------------
ALTER TABLE session       ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE session       ADD COLUMN IF NOT EXISTS app_id    VARCHAR(64);
ALTER TABLE run_record    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE run_record    ADD COLUMN IF NOT EXISTS app_id    VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_session_tenant    ON session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_run_record_tenant ON run_record(tenant_id);

-- ---- resources with full SYSTEM/TENANT/USER scope ---------------------------
ALTER TABLE skill_definition ADD COLUMN IF NOT EXISTS scope_type      VARCHAR(16);
ALTER TABLE skill_definition ADD COLUMN IF NOT EXISTS scope_tenant_id VARCHAR(64);
ALTER TABLE skill_definition ADD COLUMN IF NOT EXISTS scope_user_id   VARCHAR(64);
ALTER TABLE skill_definition ADD COLUMN IF NOT EXISTS app_id          VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_skill_scope_tenant ON skill_definition(scope_tenant_id);
CREATE INDEX IF NOT EXISTS idx_skill_scope_user   ON skill_definition(scope_user_id);

ALTER TABLE knowledge_entry ADD COLUMN IF NOT EXISTS scope_type      VARCHAR(16);
ALTER TABLE knowledge_entry ADD COLUMN IF NOT EXISTS scope_tenant_id VARCHAR(64);
ALTER TABLE knowledge_entry ADD COLUMN IF NOT EXISTS scope_user_id   VARCHAR(64);
ALTER TABLE knowledge_entry ADD COLUMN IF NOT EXISTS app_id          VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_knowledge_scope_tenant ON knowledge_entry(scope_tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_scope_user   ON knowledge_entry(scope_user_id);

ALTER TABLE memory_note ADD COLUMN IF NOT EXISTS scope_type      VARCHAR(16);
ALTER TABLE memory_note ADD COLUMN IF NOT EXISTS scope_tenant_id VARCHAR(64);
ALTER TABLE memory_note ADD COLUMN IF NOT EXISTS scope_user_id   VARCHAR(64);
ALTER TABLE memory_note ADD COLUMN IF NOT EXISTS app_id          VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_memory_scope_tenant ON memory_note(scope_tenant_id);
CREATE INDEX IF NOT EXISTS idx_memory_scope_user   ON memory_note(scope_user_id);

ALTER TABLE db_datasource ADD COLUMN IF NOT EXISTS scope_type      VARCHAR(16);
ALTER TABLE db_datasource ADD COLUMN IF NOT EXISTS scope_tenant_id VARCHAR(64);
ALTER TABLE db_datasource ADD COLUMN IF NOT EXISTS scope_user_id   VARCHAR(64);
ALTER TABLE db_datasource ADD COLUMN IF NOT EXISTS app_id          VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_datasource_scope_tenant ON db_datasource(scope_tenant_id);
CREATE INDEX IF NOT EXISTS idx_datasource_scope_user   ON db_datasource(scope_user_id);

-- agent_definition 用 visibility 而不是 scope_type,语义更贴(谁看得到这个 agent)
ALTER TABLE agent_definition ADD COLUMN IF NOT EXISTS visibility      VARCHAR(16);
ALTER TABLE agent_definition ADD COLUMN IF NOT EXISTS scope_tenant_id VARCHAR(64);
ALTER TABLE agent_definition ADD COLUMN IF NOT EXISTS scope_user_id   VARCHAR(64);
ALTER TABLE agent_definition ADD COLUMN IF NOT EXISTS app_id          VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_agent_scope_tenant ON agent_definition(scope_tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_scope_user   ON agent_definition(scope_user_id);

-- 用户"收藏"非自己所有的 SYSTEM/TENANT agent,让它出现在个人 agent 下拉里
CREATE TABLE IF NOT EXISTS user_agent_binding (
    user_id    VARCHAR(64)  NOT NULL,
    agent_id   VARCHAR(128) NOT NULL,
    PRIMARY KEY (user_id, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_uab_user ON user_agent_binding(user_id);
