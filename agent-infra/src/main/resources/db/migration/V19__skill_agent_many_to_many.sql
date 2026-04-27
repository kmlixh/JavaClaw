-- V19: skill ↔ agent 改为多对多。
-- 迁移前：skill_definition.agent_id 是强绑定；同一个 skill_name 只能挂给一个 agent。
-- 迁移后：skill_agent_binding(skill_id, agent_id) 是关系事实；skill_definition 只保留
--         "这个技能是什么"本身的定义，agent_id 字段退化为 legacy 冗余列（可 NULL），
--         所有"这个 agent 有哪些 skill"的查询都走 binding 表。

-- 1) 桥表
CREATE TABLE IF NOT EXISTS skill_agent_binding (
    skill_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (skill_id, agent_id),
    FOREIGN KEY (skill_id) REFERENCES skill_definition(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_skill_agent_binding_agent
    ON skill_agent_binding(agent_id);

-- 2) 回填：把现有每行 skill_definition 的 agent_id 变成一条 binding
INSERT INTO skill_agent_binding (skill_id, agent_id)
    SELECT id, agent_id FROM skill_definition
    WHERE agent_id IS NOT NULL
    ON CONFLICT DO NOTHING;

-- 3) 如果同名 skill 存在于多个 agent（历史数据可能有），把所有非最新那份的 binding
--    迁移到"最新 updated_at 那一行"。后续 DELETE 会 CASCADE 清掉旧行的 binding，但
--    此时数据已经抢救到幸存者身上了。
INSERT INTO skill_agent_binding (skill_id, agent_id)
SELECT s.survivor_id, sab.agent_id
FROM skill_agent_binding sab
JOIN skill_definition sd ON sab.skill_id = sd.id
JOIN (
    SELECT DISTINCT ON (skill_name) id AS survivor_id, skill_name
    FROM skill_definition
    ORDER BY skill_name, updated_at DESC, id DESC
) s ON sd.skill_name = s.skill_name
WHERE sd.id <> s.survivor_id
ON CONFLICT DO NOTHING;

-- 4) 删除非最新的重复 skill_definition（如有）。FK ON DELETE CASCADE 会连带清掉
--    它们的旧 binding，但幸存者身上已经累计了所有 agent_id。
DELETE FROM skill_definition
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY skill_name ORDER BY updated_at DESC, id DESC
        ) AS rn
        FROM skill_definition
    ) t WHERE rn > 1
);

-- 5) 替换旧的唯一约束：从 (agent_id, skill_name) → 仅 skill_name。
DROP INDEX IF EXISTS uk_skill_definition_agent_skill;
DROP INDEX IF EXISTS idx_skill_definition_agent_enabled;
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_definition_name
    ON skill_definition(skill_name);

-- 6) 放宽 agent_id 约束为可空 —— 新建 skill 时可以不指定 legacy 列，完全依赖 binding。
ALTER TABLE skill_definition ALTER COLUMN agent_id DROP NOT NULL;
