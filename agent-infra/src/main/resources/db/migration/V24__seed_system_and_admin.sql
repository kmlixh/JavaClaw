-- ============================================================================
-- P1 M3: 种系统租户 / 默认 app / 权限目录 / SUPER_ADMIN 角色 / admin 用户占位,
--        再把现有资源 backfill 到 SYSTEM scope。
-- ----------------------------------------------------------------------------
-- admin 的 password_hash 先写一个显式占位 ("PENDING_INITIALIZATION"),后端
-- FirstAdminPasswordInitializer 启动时检测到就生成真实随机密码,BCrypt 入库,
-- 明文落 workspace/FIRST_ADMIN_PASSWORD.txt + stdout 打一次。
-- ============================================================================

-- ---- SYSTEM tenant + default app --------------------------------------------
INSERT INTO tenant (id, code, name, kind, status, description)
VALUES ('system', 'SYSTEM', '系统', 'SYSTEM', 'ACTIVE',
        'System-level tenant. Hosts global agents / skills / knowledge / datasources visible to every user.')
ON CONFLICT (id) DO NOTHING;

INSERT INTO application (id, tenant_id, code, name, description)
VALUES ('system-default', 'system', 'default', '系统默认应用',
        'Default application container for system-scoped resources.')
ON CONFLICT (id) DO NOTHING;

-- ---- 权限目录 (一次性 seed,后续新权限走 migration) -------------------------
INSERT INTO permission (code, category, description) VALUES
    ('menu.chat',            'menu',  '会话菜单'),
    ('menu.agents',          'menu',  'Agents 管理菜单'),
    ('menu.llms',            'menu',  'LLM 管理菜单'),
    ('menu.skills',          'menu',  'Skills 管理菜单'),
    ('menu.knowledge',       'menu',  '知识库管理菜单'),
    ('menu.memory',          'menu',  '长期记忆管理菜单'),
    ('menu.datasources',     'menu',  '数据源管理菜单'),
    ('menu.approvals',       'menu',  '审批管理菜单'),
    ('menu.search',          'menu',  '搜索菜单'),
    ('menu.users',           'menu',  '用户管理菜单'),
    ('menu.tenants',         'menu',  '租户管理菜单'),

    ('agent.read',           'data',  '查看 agent'),
    ('agent.edit',           'data',  '编辑 agent'),
    ('agent.bind.user',      'data',  '把 agent 绑定到用户'),
    ('skill.manage',         'data',  '管理 skill (创建/编辑/删除)'),
    ('knowledge.manage',     'data',  '管理知识库'),
    ('memory.manage',        'data',  '管理长期记忆'),
    ('datasource.manage',    'data',  '管理数据源'),
    ('llm.manage',           'data',  '管理 LLM 配置'),

    ('session.read.own',     'data',  '读自己的会话'),
    ('session.read.tenant',  'data',  '读租户下所有会话'),
    ('session.read.all',     'data',  '读所有会话(跨租户)'),
    ('session.terminate',    'data',  '终止运行中的 run'),

    ('user.manage',          'admin', '管理用户(CRUD + 角色分配)'),
    ('tenant.manage',        'admin', '管理租户'),
    ('permission.manage',    'admin', '管理权限 / 角色'),
    ('oauth.client.manage',  'admin', '管理外部 OAuth 应用客户端')
ON CONFLICT (code) DO NOTHING;

-- ---- SYSTEM 租户的 SUPER_ADMIN 角色 + USER 基本角色 -------------------------
INSERT INTO role (id, tenant_id, code, name, description)
VALUES
    ('system-super-admin', 'system', 'SUPER_ADMIN', '超级管理员',
     'Has every permission including cross-tenant management and OAuth client admin.'),
    ('system-user',        'system', 'USER',        '普通用户',
     'Basic chat usage on the system tenant. Can manage own private skills/knowledge/memory.')
ON CONFLICT (id) DO NOTHING;

-- SUPER_ADMIN: 全量权限
INSERT INTO role_permission (role_id, permission_code)
SELECT 'system-super-admin', code FROM permission
ON CONFLICT DO NOTHING;

-- USER: 只有基础菜单 + 自己的会话 + 自己私有资源的管理 + 基础 agent 查看
INSERT INTO role_permission (role_id, permission_code) VALUES
    ('system-user', 'menu.chat'),
    ('system-user', 'menu.agents'),
    ('system-user', 'menu.skills'),
    ('system-user', 'menu.knowledge'),
    ('system-user', 'menu.memory'),
    ('system-user', 'menu.datasources'),
    ('system-user', 'menu.approvals'),
    ('system-user', 'menu.search'),
    ('system-user', 'agent.read'),
    ('system-user', 'skill.manage'),      -- 只能管自己私有的(runtime 层按 scope_user_id 限制)
    ('system-user', 'knowledge.manage'),
    ('system-user', 'memory.manage'),
    ('system-user', 'datasource.manage'),
    ('system-user', 'session.read.own'),
    ('system-user', 'session.terminate')
ON CONFLICT DO NOTHING;

-- ---- admin 用户占位:password_hash 由 Runtime 启动时覆盖为 BCrypt 真值 -----
INSERT INTO app_user (id, username, password_hash, display_name, status,
                      preferred_tenant_id, password_must_change)
VALUES ('admin', 'admin', 'PENDING_INITIALIZATION', '系统管理员', 'ACTIVE',
        'system', TRUE)
ON CONFLICT (id) DO NOTHING;

-- admin 在 SYSTEM 租户里是 SUPER_ADMIN
INSERT INTO user_tenant_role (user_id, tenant_id, role_id)
VALUES ('admin', 'system', 'system-super-admin')
ON CONFLICT DO NOTHING;

-- ---- SYSTEM 租户所有菜单开 ---------------------------------------------------
INSERT INTO tenant_menu (tenant_id, menu_code, enabled, sort_order) VALUES
    ('system', 'chat',         TRUE, 1),
    ('system', 'agents',       TRUE, 2),
    ('system', 'llms',         TRUE, 3),
    ('system', 'skills',       TRUE, 4),
    ('system', 'knowledge',    TRUE, 5),
    ('system', 'memory',       TRUE, 6),
    ('system', 'datasources',  TRUE, 7),
    ('system', 'approvals',    TRUE, 8),
    ('system', 'search',       TRUE, 9),
    ('system', 'users',        TRUE, 10),
    ('system', 'tenants',      TRUE, 11)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Backfill: 现有所有数据归 SYSTEM 租户 / system-default 应用
-- ============================================================================

-- session / run_record
UPDATE session    SET tenant_id='system', app_id='system-default' WHERE tenant_id IS NULL;
UPDATE run_record SET tenant_id='system', app_id='system-default' WHERE tenant_id IS NULL;

-- skill / knowledge / memory / datasource: 全归 SYSTEM scope
UPDATE skill_definition SET scope_type='SYSTEM', app_id='system-default' WHERE scope_type IS NULL;
UPDATE knowledge_entry  SET scope_type='SYSTEM', app_id='system-default' WHERE scope_type IS NULL;
UPDATE memory_note      SET scope_type='SYSTEM', app_id='system-default' WHERE scope_type IS NULL;
UPDATE db_datasource    SET scope_type='SYSTEM', app_id='system-default' WHERE scope_type IS NULL;

-- agent: 默认 SYSTEM 可见
UPDATE agent_definition SET visibility='SYSTEM', app_id='system-default' WHERE visibility IS NULL;

-- backfill 完,加 NOT NULL 约束避免之后的行漏填
ALTER TABLE skill_definition ALTER COLUMN scope_type SET NOT NULL;
ALTER TABLE knowledge_entry  ALTER COLUMN scope_type SET NOT NULL;
ALTER TABLE memory_note      ALTER COLUMN scope_type SET NOT NULL;
ALTER TABLE db_datasource    ALTER COLUMN scope_type SET NOT NULL;
ALTER TABLE agent_definition ALTER COLUMN visibility SET NOT NULL;
