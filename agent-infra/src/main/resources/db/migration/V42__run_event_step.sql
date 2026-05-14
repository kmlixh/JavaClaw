-- V42: 把 run 复盘事件从 run_record.event_log_json 单一 JSON 大字符串拆成行级表,
-- 每条事件一行,显式带 category / event_type / tool_name 列。
--
-- 动机:
--   1. 大 run 可能产 50MB+ JSON,前端打开复盘要 parse 整段才能过滤 → 卡;
--   2. 想按"只看 SQL"、"只看 LLM 调用"在数据库层过滤 → JSON 字段做不到;
--   3. 检索 "哪几次 run 调过 db.query in xmap" 需要全表 scan JSON;
--   4. 单条事件 ~16KB payload 是上限,行级表无此约束(每行 TEXT 列单独存)。
--
-- 老数据兼容:旧 run 没行级数据,后端 fallback 到 run_record.event_log_json 解析。
-- 新 run 完成 flush 时,RunEventCollector 把每条事件 insert 一行到本表,
-- event_log_json 列继续写(灾备用,不依赖)。

CREATE TABLE IF NOT EXISTS run_event_step (
    id                BIGSERIAL PRIMARY KEY,
    run_id            VARCHAR(64) NOT NULL,
    session_id        VARCHAR(64),
    seq               INTEGER NOT NULL,
    ts                TIMESTAMPTZ NOT NULL,
    -- 分类:ai / db / file / artifact / plan / tool-other / lifecycle
    -- 跟前端 chip 过滤一一对应;新增分类时这里加值,不需要 schema 改动
    category          VARCHAR(16) NOT NULL,
    -- 原始事件类型:PROMPT_SENT / LLM_RESPONSE / TOOL_REQUESTED / TOOL_COMPLETED 等
    event_type        VARCHAR(64) NOT NULL,
    -- 工具类事件填具体工具名(db.query / doc.normalize / artifact.markdown 等);其它事件 NULL
    tool_name         VARCHAR(128),
    iteration_no      INTEGER,
    -- 一句话摘要,前端列表直接显示这一行,不用展开 payload
    summary           TEXT,
    -- 完整事件 payload,JSON,TEXT 无上限(PostgreSQL ~1GB)
    payload_json      TEXT,
    duration_ms       BIGINT,
    success           BOOLEAN
);

-- 主访问模式:按 run_id 拉时间线
CREATE UNIQUE INDEX IF NOT EXISTS uk_run_event_step_run_seq
    ON run_event_step(run_id, seq);
-- 分类过滤
CREATE INDEX IF NOT EXISTS idx_run_event_step_run_cat
    ON run_event_step(run_id, category);
-- 按工具名跨 run 查
CREATE INDEX IF NOT EXISTS idx_run_event_step_tool
    ON run_event_step(tool_name) WHERE tool_name IS NOT NULL;
-- 跨 session 查所有事件(很少用但成本低)
CREATE INDEX IF NOT EXISTS idx_run_event_step_session
    ON run_event_step(session_id) WHERE session_id IS NOT NULL;
