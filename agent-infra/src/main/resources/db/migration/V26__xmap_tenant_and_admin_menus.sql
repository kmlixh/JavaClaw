-- V26: 补全管理类菜单 + 系统超管菜单权限 + 新增 xmap 租户与 Xmap OAuth 应用。
-- 全部 ON CONFLICT DO NOTHING,可以重复跑(直接 psql 执行 + Flyway 重跑都安全)。

-- ── 1. 缺失的菜单类权限 ────────────────────────────────────────────────────
INSERT INTO permission (code, category, description) VALUES
    ('menu.roles',         'menu',  '角色与权限管理菜单'),
    ('menu.oauth-clients', 'menu',  'OAuth 应用管理菜单')
ON CONFLICT (code) DO NOTHING;

-- ── 2. SYSTEM 超管补齐"角色 / OAuth 应用"两个菜单(其它早已 SELECT * 灌入) ──
INSERT INTO role_permission (role_id, permission_code)
SELECT 'system-super-admin', code FROM permission
ON CONFLICT DO NOTHING;

-- ── 3. SYSTEM 租户开启新菜单 ──────────────────────────────────────────────
INSERT INTO tenant_menu (tenant_id, menu_code, enabled, sort_order) VALUES
    ('system', 'roles',         TRUE, 12),
    ('system', 'oauth-clients', TRUE, 13)
ON CONFLICT DO NOTHING;

-- ── 4. xmap 租户 ─────────────────────────────────────────────────────────
INSERT INTO tenant (id, code, name, kind, status, description) VALUES
    ('xmap', 'xmap', 'XMap 租户', 'TENANT', 'ACTIVE', 'XMap 业务租户')
ON CONFLICT (id) DO NOTHING;

INSERT INTO application (id, tenant_id, code, name, description) VALUES
    ('xmap-default', 'xmap', 'xmap-default', 'XMap 默认应用', 'XMap 租户的默认应用')
ON CONFLICT (id) DO NOTHING;

-- ── 5. xmap 角色 ─────────────────────────────────────────────────────────
INSERT INTO role (id, tenant_id, code, name, description) VALUES
    ('xmap-tenant-admin', 'xmap', 'TENANT_ADMIN', 'XMap 租户管理员',
     '可在 xmap 租户内管理用户、看本租户所有会话、管理本租户私有资源'),
    ('xmap-user',         'xmap', 'USER',         'XMap 普通用户',
     '可使用 chat 与本人私有资源管理')
ON CONFLICT (id) DO NOTHING;

-- xmap-tenant-admin 权限集合
INSERT INTO role_permission (role_id, permission_code) VALUES
    ('xmap-tenant-admin', 'menu.chat'),
    ('xmap-tenant-admin', 'menu.agents'),
    ('xmap-tenant-admin', 'menu.skills'),
    ('xmap-tenant-admin', 'menu.knowledge'),
    ('xmap-tenant-admin', 'menu.memory'),
    ('xmap-tenant-admin', 'menu.datasources'),
    ('xmap-tenant-admin', 'menu.approvals'),
    ('xmap-tenant-admin', 'menu.search'),
    ('xmap-tenant-admin', 'menu.users'),
    ('xmap-tenant-admin', 'agent.read'),
    ('xmap-tenant-admin', 'agent.edit'),
    ('xmap-tenant-admin', 'agent.bind.user'),
    ('xmap-tenant-admin', 'skill.manage'),
    ('xmap-tenant-admin', 'knowledge.manage'),
    ('xmap-tenant-admin', 'memory.manage'),
    ('xmap-tenant-admin', 'datasource.manage'),
    ('xmap-tenant-admin', 'session.read.tenant'),
    ('xmap-tenant-admin', 'session.terminate'),
    ('xmap-tenant-admin', 'user.manage')
ON CONFLICT DO NOTHING;

-- xmap-user 权限集合
INSERT INTO role_permission (role_id, permission_code) VALUES
    ('xmap-user', 'menu.chat'),
    ('xmap-user', 'menu.agents'),
    ('xmap-user', 'menu.skills'),
    ('xmap-user', 'menu.knowledge'),
    ('xmap-user', 'menu.memory'),
    ('xmap-user', 'menu.datasources'),
    ('xmap-user', 'menu.approvals'),
    ('xmap-user', 'menu.search'),
    ('xmap-user', 'agent.read'),
    ('xmap-user', 'skill.manage'),
    ('xmap-user', 'knowledge.manage'),
    ('xmap-user', 'memory.manage'),
    ('xmap-user', 'datasource.manage'),
    ('xmap-user', 'session.read.own'),
    ('xmap-user', 'session.terminate')
ON CONFLICT DO NOTHING;

-- ── 6. xmap 租户菜单 ─────────────────────────────────────────────────────
INSERT INTO tenant_menu (tenant_id, menu_code, enabled, sort_order) VALUES
    ('xmap', 'chat',           TRUE,  1),
    ('xmap', 'agents',         TRUE,  2),
    ('xmap', 'skills',         TRUE,  4),
    ('xmap', 'knowledge',      TRUE,  5),
    ('xmap', 'memory',         TRUE,  6),
    ('xmap', 'datasources',    TRUE,  7),
    ('xmap', 'approvals',      TRUE,  8),
    ('xmap', 'search',         TRUE,  9),
    ('xmap', 'users',          TRUE, 10)
ON CONFLICT DO NOTHING;

-- ── 7. Xmap OAuth 客户端 ────────────────────────────────────────────────
-- 用 pgcrypto 算 BCrypt $2a$10$,与 Spring BCryptPasswordEncoder 兼容。
-- 默认 secret 是固定字符串,仅用于初始化,**强烈建议** 上线前在管理界面 rotate。
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO oauth_client (
    client_id, client_secret_hash, display_name, redirect_uris, scopes,
    status, owner_user_id, description
) VALUES (
    'xmap',
    crypt('xmap-init-secret-2026-04-27', gen_salt('bf', 10)),
    'Xmap',
    '["http://localhost:8080/oauth/callback","https://xmap.example.com/oauth/callback"]',
    '["openid","profile","chat.read","chat.write"]',
    'ACTIVE',
    'admin',
    'XMap 业务系统使用的外部 OAuth 客户端。初始 secret=xmap-init-secret-2026-04-27,请尽快通过 UI 旋转。'
)
ON CONFLICT (client_id) DO NOTHING;
