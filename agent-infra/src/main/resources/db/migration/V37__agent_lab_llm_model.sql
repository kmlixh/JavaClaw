-- Lab 任务记录 meta/test LLM 选定的 apiModel。
-- 之前只存 config_id,但一个 LlmProviderConfig 可挂多个 model(modelMappingJson.models),
-- 用户在新建实验室任务时实际选的是 (configId, model) 对 —— 跟会话面板的 llmModel 同思路。
ALTER TABLE agent_lab_task ADD COLUMN IF NOT EXISTS meta_llm_model VARCHAR(128);
ALTER TABLE agent_lab_task ADD COLUMN IF NOT EXISTS test_llm_model VARCHAR(128);
