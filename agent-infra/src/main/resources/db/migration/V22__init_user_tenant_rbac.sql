-- ============================================================================
-- P1 M1: 用户 / 租户 / 角色 / 权限 基础表
-- ----------------------------------------------------------------------------
-- 这一步只建表,不改现有查询路径。SecurityContext 仍然把匿名请求映射到 SYSTEM 租户
-- + 默认 admin 用户(V24 种下),既有功能不受影响。
--
-- 设计要点:
--   - "user" 是多数 DB 的保留字,表名用 app_user 规避歧义。
--   - 租户 tenant 里 kind='SYSTEM' 的唯一特殊行 id='system' 代表系统级租户,
--     host 系统预置 agent/skill/knowledge 等全局资源。避免代码到处 if-is-system。
--   - role 是租户级的(tenant_id NOT NULL)。系统级超管角色挂在 SYSTEM 租户下即可。
--   - permission 是全局平铺 code 目录,不按租户隔离 —— 所有租户共享同一套能力名。
--   - user_permission.effect 支持 DENY,角色授予但用户单独拒;DENY 优先级 > GRANT。
--   - tenant_menu 是租户对"哪些菜单可见"的开关,和 permission 是 AND 关系:
--     菜单必须 tenant_menu.enabled=TRUE 且用户有 menu.<code> permission。
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_user (
    id                    VARCHAR(64) PRIMARY KEY,
    username              VARCHAR(64) NOT NULL UNIQUE,
    email                 VARCHAR(128),
    password_hash         TEXT NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | DISABLED | LOCKED
    preferred_tenant_id   VARCHAR(64),
    password_must_change  BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at         TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_email ON app_user(email) WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS tenant (
    id           VARCHAR(64) PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,            -- 人类可读 slug,如 'SYSTEM' / 'acme'
    name         VARCHAR(128) NOT NULL,
    kind         VARCHAR(32) NOT NULL DEFAULT 'TENANT',  -- TENANT | SYSTEM
    status       VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED
    description  TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS application (
    id           VARCHAR(64) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    code         VARCHAR(64) NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, code)
);
CREATE INDEX IF NOT EXISTS idx_application_tenant ON application(tenant_id);

CREATE TABLE IF NOT EXISTS permission (
    code         VARCHAR(64) PRIMARY KEY,
    category     VARCHAR(32) NOT NULL DEFAULT 'data',    -- menu | data | admin | system
    description  TEXT
);

CREATE TABLE IF NOT EXISTS role (
    id           VARCHAR(64) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    code         VARCHAR(64) NOT NULL,                   -- SUPER_ADMIN / ADMIN / USER / VIEWER ...
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, code)
);
CREATE INDEX IF NOT EXISTS idx_role_tenant ON role(tenant_id);

CREATE TABLE IF NOT EXISTS role_permission (
    role_id          VARCHAR(64) NOT NULL,
    permission_code  VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission_code)
);
CREATE INDEX IF NOT EXISTS idx_role_permission_role ON role_permission(role_id);

CREATE TABLE IF NOT EXISTS user_tenant_role (
    user_id      VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    role_id      VARCHAR(64) NOT NULL,
    granted_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, tenant_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_utr_user ON user_tenant_role(user_id);
CREATE INDEX IF NOT EXISTS idx_utr_tenant ON user_tenant_role(tenant_id);

CREATE TABLE IF NOT EXISTS user_permission (
    user_id          VARCHAR(64) NOT NULL,
    tenant_id        VARCHAR(64) NOT NULL,
    permission_code  VARCHAR(64) NOT NULL,
    effect           VARCHAR(16) NOT NULL DEFAULT 'GRANT',  -- GRANT | DENY
    granted_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, tenant_id, permission_code)
);
CREATE INDEX IF NOT EXISTS idx_up_user ON user_permission(user_id);

CREATE TABLE IF NOT EXISTS tenant_menu (
    tenant_id    VARCHAR(64) NOT NULL,
    menu_code    VARCHAR(64) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, menu_code)
);
