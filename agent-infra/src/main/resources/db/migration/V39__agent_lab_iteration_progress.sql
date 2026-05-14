-- V39: agent_lab_iteration 加 progress_step 列。
-- 之前迭代中 status='IN_PROGRESS' 但所有 *_json / evaluation_summary 字段都 null,
-- 前端展开"查看 Agent/Skill/测试结果"什么都看不到。runner 会在每个步骤(调 meta-LLM、
-- 跑测试场景 i/N、评估)更新 progress_step,前端实时展示进度。
ALTER TABLE agent_lab_iteration ADD COLUMN IF NOT EXISTS progress_step TEXT;
