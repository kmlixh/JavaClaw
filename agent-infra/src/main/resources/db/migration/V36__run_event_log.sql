-- V36: 给 run_record 加 event_log_json,存整个 run 的过程链路 JSON 数组,
-- 用于事后完整复盘(前端可渲染成时间线 / 流程图)。
--
-- 单个 run 一行 JSON 数组,每条事件:
--   { "ts": "...", "seq": 1, "type": "RUN_STARTED|PROMPT_SENT|LLM_RESPONSE|TOOL_*|PLAN_UPDATED|...",
--     "data": { ... type-specific fields ... } }
--
-- 不新建独立表 —— 一个 run 完整复盘最自然的存储是单字段 JSON 数组(PG TOAST 自动压缩),
-- 也避免跨表 join 复盘时的复杂度。仍跟 session_message / tool_audit_log 保持冗余,
-- 这两张表用于按消息 / 工具维度查询,event_log_json 用于按时序复盘。

ALTER TABLE run_record
    ADD COLUMN IF NOT EXISTS event_log_json TEXT;
