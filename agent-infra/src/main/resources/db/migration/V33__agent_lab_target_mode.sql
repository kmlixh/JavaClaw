-- V33: Agent 实验室重构 —— 从"强制 sandbox 隔离"改为"对指定 Agent 直接迭代调试"。
--
-- 三种创建模式:
--   * EXISTING:    选已有 agent,直接在它上面迭代修改
--   * NEW:         lab 内现场新建 agent,用户指定 agent_id + scope
--   * CLONE_FROM:  从源 agent 克隆配置作为起点,源不受影响
--
-- 跑测试不再是"调一次 LLM",而是用 SimpleAgentRunner 跑完整 chat(plan/tool loop/SkillGuard 全过),
-- 把完整 trace 存到 iteration.run_traces_json 喂给 meta-LLM 分析。

ALTER TABLE agent_lab_task
    ADD COLUMN IF NOT EXISTS mode                    VARCHAR(16) NOT NULL DEFAULT 'NEW',
    ADD COLUMN IF NOT EXISTS target_agent_id         VARCHAR(128),
    ADD COLUMN IF NOT EXISTS target_scope_type       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS target_scope_tenant_id  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS clone_from_agent_id     VARCHAR(128);

ALTER TABLE agent_lab_iteration
    ADD COLUMN IF NOT EXISTS run_traces_json TEXT;

-- 老任务(若有)迁移:把 sandbox_agent_id 当作 target_agent_id,模式标 NEW。
UPDATE agent_lab_task
SET target_agent_id = COALESCE(target_agent_id, sandbox_agent_id),
    mode            = COALESCE(NULLIF(mode, ''), 'NEW'),
    target_scope_type = COALESCE(target_scope_type, 'SYSTEM')
WHERE target_agent_id IS NULL;
