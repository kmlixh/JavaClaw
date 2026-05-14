-- V43: 给 run_record 加 raw_log_text TEXT 字段,存整个 run 期间**完整未过滤**的事件序列。
--
-- 跟现有两条复盘路径的差异:
--   * run_record.event_log_json (V36):去掉 TOKEN_DELTA + RUN_STATUS phase=MODEL_OUTPUT 的"去噪"
--                                    JSON 数组,前端 timeline / 流程图用这个;
--   * run_event_step          (V42):每条事件一行的关系表,前端按 category 过滤用这个;
--   * run_record.raw_log_text (本):**包含 TOKEN_DELTA / 流式 MODEL_OUTPUT chunk 在内的完整 JSON
--                                  数组**,用作"取一切"的查询。debug LLM 流式响应、回看 token 用量
--                                  逐 chunk 怎么累加、查"为什么这一刻 UI 卡了 X 秒"等场景必需。
--
-- 类型选 TEXT 不是 jsonb:不需要 Postgres 索引/查询字段子树,只需要整段 select 出来给前端/人看。
-- 而且 TEXT 在 toast 存储上比 jsonb 省。
ALTER TABLE run_record ADD COLUMN IF NOT EXISTS raw_log_text TEXT;

COMMENT ON COLUMN run_record.raw_log_text IS
    'Complete unfiltered per-run event log (JSON array). Differs from event_log_json by including TOKEN_DELTA and streaming MODEL_OUTPUT chunks. Written once at run end by RunEventCollector.flushAndPersist.';
