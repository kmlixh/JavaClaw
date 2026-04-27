-- Source of truth for connection info lives in `knowledge_entry` (admins edit it through
-- the knowledge-base UI); `db_datasource` is a resource materialization of that knowledge.
-- This migration parses the existing Xmap 数据库 knowledge entry and upserts the gis-yunnan
-- datasource from it, so the two stay in sync without hand-editing seed SQL.
--
-- Expected knowledge entry format (title='Xmap数据库', 全角逗号分隔):
--   数据库地址：<ip>:<port>，数据库名称：<db>，用户名：<user>，密码：<pwd>
--
-- If the entry is missing or the format drifts, the DO block raises NOTICE and leaves the
-- existing db_datasource row untouched — migrations must not silently swallow data loss.
DO $$
DECLARE
    src_content TEXT;
    src_id TEXT;
    host_port TEXT;
    dbname TEXT;
    uname TEXT;
    pwd TEXT;
BEGIN
    SELECT id, content
      INTO src_id, src_content
      FROM knowledge_entry
     WHERE title = 'Xmap数据库' AND enabled = TRUE
     ORDER BY updated_at DESC
     LIMIT 1;

    IF src_content IS NULL THEN
        RAISE NOTICE 'V13 skip: knowledge_entry with title=Xmap数据库 not found; leaving db_datasource unchanged.';
        RETURN;
    END IF;

    host_port := substring(src_content from '数据库地址：([0-9.]+:[0-9]+)');
    dbname    := substring(src_content from '数据库名称：([^，,]+)');
    uname     := substring(src_content from '用户名：([^，,]+)');
    pwd       := substring(src_content from '密码：([^，,]+)');

    IF host_port IS NULL OR dbname IS NULL OR uname IS NULL OR pwd IS NULL THEN
        RAISE NOTICE 'V13 skip: could not parse host/db/user/pwd from knowledge_entry %; content=%', src_id, src_content;
        RETURN;
    END IF;

    INSERT INTO db_datasource (id, display_name, jdbc_url, username, password, dialect, description, enabled)
    VALUES (
        'gis-yunnan',
        'GIS 云南（派生自知识库 Xmap数据库）',
        'jdbc:postgresql://' || host_port || '/' || dbname,
        uname,
        pwd,
        'postgresql',
        'Derived from knowledge_entry id=' || src_id || '. Edit that entry and re-sync to update credentials.',
        TRUE
    )
    ON CONFLICT (id) DO UPDATE
      SET display_name = EXCLUDED.display_name,
          jdbc_url     = EXCLUDED.jdbc_url,
          username     = EXCLUDED.username,
          password     = EXCLUDED.password,
          dialect      = EXCLUDED.dialect,
          description  = EXCLUDED.description,
          enabled      = EXCLUDED.enabled,
          updated_at   = CURRENT_TIMESTAMP;

    RAISE NOTICE 'V13 synced db_datasource gis-yunnan from knowledge_entry %', src_id;
END $$;
