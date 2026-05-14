-- V38: Agent 实验室任务从"用户手写测试用例"改成"用户写约束规则,AI 派生测试场景"。
-- 新增 constraint_rules / reference_documents 两列,二者均可选(NULL = 老任务,迁移前没填)。
-- test_cases_json 保留(runner 仍用它跑评估),但内容改成由 meta-LLM 自动生成。
-- 老任务读出来时若 constraint_rules 为 NULL,前端展示 testCasesJson 兼容显示。
ALTER TABLE agent_lab_task ADD COLUMN IF NOT EXISTS constraint_rules TEXT;
ALTER TABLE agent_lab_task ADD COLUMN IF NOT EXISTS reference_documents TEXT;
