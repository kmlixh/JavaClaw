-- Named datasource resources: skills reference these by jdbc_url or id, and the agent
-- runtime auto-injects credentials into tool calls so the LLM never sees or manages secrets.
CREATE TABLE IF NOT EXISTS db_datasource (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    jdbc_url VARCHAR(512) NOT NULL,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(512) NOT NULL,
    dialect VARCHAR(32),
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One row per distinct jdbc_url so lookups by URL are unambiguous.
CREATE UNIQUE INDEX IF NOT EXISTS db_datasource_jdbc_url_uk ON db_datasource (jdbc_url);

-- Seed: the GIS / Yunnan dataset the coverage-analysis skill needs. Credentials stored
-- in plaintext here are a placeholder — wire up encryption (or HashiCorp Vault / KMS) in
-- a follow-up if production secrets need protection at rest.
INSERT INTO db_datasource (id, display_name, jdbc_url, username, password, dialect, description)
VALUES (
    'gis-yunnan',
    'GIS 云南（覆盖分析主库）',
    'jdbc:postgresql://10.174.238.4:5432/gis',
    'gisuser',
    'gMXgati8UCmUFAgyXxXv',
    'postgresql',
    '云南移动 GIS 数据库，含覆盖分析 skill 依赖的 xmap / xmap_ott / xmap_deal / ott_temp 全部表'
)
ON CONFLICT (id) DO UPDATE
  SET display_name = EXCLUDED.display_name,
      jdbc_url = EXCLUDED.jdbc_url,
      username = EXCLUDED.username,
      password = EXCLUDED.password,
      dialect = EXCLUDED.dialect,
      description = EXCLUDED.description,
      updated_at = CURRENT_TIMESTAMP;
