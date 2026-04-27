-- V27: 给 oauth_client 加 tenant_id;创建 xmap-admin 用户;补全 xmap-tenant-admin 权限。
-- idempotent,可重跑。

-- ── 1. oauth_client.tenant_id ───────────────────────────────────────────
ALTER TABLE oauth_client ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- 把已有 'xmap' client 归到 xmap 租户;其它已有(仅理论上的旧数据)落到 system。
UPDATE oauth_client SET tenant_id = 'xmap'   WHERE client_id = 'xmap'   AND tenant_id IS NULL;
UPDATE oauth_client SET tenant_id = 'system' WHERE tenant_id IS NULL;

ALTER TABLE oauth_client ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_oauth_client_tenant ON oauth_client(tenant_id);

-- ── 2. 补 xmap-tenant-admin 角色权限 ────────────────────────────────────
INSERT INTO role_permission (role_id, permission_code) VALUES
    ('xmap-tenant-admin', 'menu.tenants'),       -- 只能看自己租户(后端 filter 兜)
    ('xmap-tenant-admin', 'menu.oauth-clients'),
    ('xmap-tenant-admin', 'oauth.client.manage')
ON CONFLICT DO NOTHING;

-- ── 3. xmap 租户开 oauth-clients 菜单 ──────────────────────────────────
INSERT INTO tenant_menu (tenant_id, menu_code, enabled, sort_order) VALUES
    ('xmap', 'oauth-clients', TRUE, 13)
ON CONFLICT DO NOTHING;

-- ── 4. xmap-admin 用户(初始密码 xmap-admin-init-2026,首次登录强制改) ──
INSERT INTO app_user (
    id, username, password_hash, display_name, email,
    status, preferred_tenant_id, password_must_change
) VALUES (
    'xmap-admin', 'xmap-admin',
    crypt('xmap-admin-init-2026', gen_salt('bf', 10)),
    'XMap 租户管理员', NULL,
    'ACTIVE', 'xmap', TRUE
)
ON CONFLICT (id) DO NOTHING;

-- ── 5. 绑定 xmap-admin → xmap-tenant-admin (在 xmap 租户内) ─────────────
INSERT INTO user_tenant_role (user_id, tenant_id, role_id) VALUES
    ('xmap-admin', 'xmap', 'xmap-tenant-admin')
ON CONFLICT DO NOTHING;
