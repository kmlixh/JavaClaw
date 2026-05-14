-- V35: Agent 实验室 —— 允许迭代过程中也改 Host Agent(默认仍只改 Skill)。
--
-- 开关含义:
--   false (默认) —— 每轮 meta-LLM 只输出 Skill 设计,Agent 保持创建时的状态不动
--   true         —— 每轮 meta-LLM 可同时输出 Agent + Skill 设计,Runner 都覆盖
--
-- Phase 1.5 新建 Host Agent 时还允许写一句"种子提示词"(seed prompt),meta-LLM 第一轮会
-- 据此扩写正式 systemPrompt。但 seed prompt 不入库 —— 落到 host agent 自己的 systemPrompt
-- 字段后就没必要再存,详情页要看就直接看 Agent 配置。

ALTER TABLE agent_lab_task
    ADD COLUMN IF NOT EXISTS allow_agent_evolution BOOLEAN NOT NULL DEFAULT FALSE;
