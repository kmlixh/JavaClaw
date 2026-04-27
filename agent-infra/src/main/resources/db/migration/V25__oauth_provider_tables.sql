-- ============================================================================
-- P2: 让外部应用通过 OAuth2 授权码流程拿到我们系统的 access token,代表用户调用
-- 我们的开放 API。
--
-- 表设计是简化版:code 只存一次、必须在短 TTL 内兑换;access token 不再存表,
-- 直接用 JwtService 颁发 HS256 JWT(typ='oauth'),减少一次 DB 往返。这样 revoke
-- 变成"加短 deny list",由 oauth_access_revocation 承载,启动时预加载到内存。
-- ============================================================================

-- 外部应用客户端注册
CREATE TABLE IF NOT EXISTS oauth_client (
    client_id            VARCHAR(64) PRIMARY KEY,
    client_secret_hash   TEXT NOT NULL,                 -- BCrypt(secret)
    display_name         VARCHAR(128) NOT NULL,
    redirect_uris        TEXT NOT NULL,                 -- JSON array of allowed redirect URIs
    scopes               TEXT NOT NULL DEFAULT '[]',    -- JSON array of scope strings
    status               VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED
    owner_user_id        VARCHAR(64),                   -- 谁创建的(用于管理)
    description          TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 临时授权码(用户同意后到换 token 前的一次性凭证)
CREATE TABLE IF NOT EXISTS oauth_authorization_code (
    code             VARCHAR(128) PRIMARY KEY,            -- 随机 URL-safe 64+ 字符
    client_id        VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,               -- 用户同意时所在的租户
    redirect_uri     TEXT         NOT NULL,
    scopes           TEXT         NOT NULL DEFAULT '[]',
    code_challenge   VARCHAR(128),                        -- PKCE,可选
    code_challenge_method VARCHAR(16),                    -- S256 | plain
    expires_at       TIMESTAMP    NOT NULL,               -- 默认 10 分钟
    consumed         BOOLEAN      NOT NULL DEFAULT FALSE, -- 兑换后置 TRUE,重复使用拒
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oauth_code_client ON oauth_authorization_code(client_id);
CREATE INDEX IF NOT EXISTS idx_oauth_code_expires ON oauth_authorization_code(expires_at);

-- 主动撤销的 access token(deny list)。jti 是 JWT 的 jti claim
CREATE TABLE IF NOT EXISTS oauth_access_revocation (
    jti        VARCHAR(128) PRIMARY KEY,
    client_id  VARCHAR(64),
    user_id    VARCHAR(64),
    expires_at TIMESTAMP NOT NULL,          -- 跟 token exp 对齐,超时清理
    revoked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason     TEXT
);

-- 新增权限:oauth.client.manage 已在 V24 种过了,这里无需补
