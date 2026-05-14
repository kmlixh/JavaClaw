-- V34: Agent 实验室 - 加入"调试 Skill"维度,LLM 拆分 meta / test 两栏。
--
-- 之前 V33 只支持调试 Agent,但 Agent 可改的字段少(systemPrompt/agentMarkdown);
-- Skill 才是承载能力的载体(promptTemplate / configJson 含 whitelistTables /
-- planStepRules / sqlTemplates 等)。这一版让 task 可选 target_type=SKILL,
-- meta-LLM 只改 Skill,Agent 当宿主不动。
--
-- LLM 也拆成:
--   * meta_llm_config_id —— 设计师 LLM,默认 default
--   * test_llm_config_id —— 跑测试用的 LLM,留空走 Agent 默认

ALTER TABLE agent_lab_task
    ADD COLUMN IF NOT EXISTS target_type           VARCHAR(16) NOT NULL DEFAULT 'SKILL',
    ADD COLUMN IF NOT EXISTS target_skill_name     VARCHAR(128),
    ADD COLUMN IF NOT EXISTS new_skill_name        VARCHAR(128),
    ADD COLUMN IF NOT EXISTS clone_from_skill_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS meta_llm_config_id    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS test_llm_config_id    VARCHAR(64);

UPDATE agent_lab_task
SET meta_llm_config_id = COALESCE(meta_llm_config_id, llm_config_id),
    target_type        = COALESCE(NULLIF(target_type, ''), 'AGENT')
WHERE meta_llm_config_id IS NULL;
