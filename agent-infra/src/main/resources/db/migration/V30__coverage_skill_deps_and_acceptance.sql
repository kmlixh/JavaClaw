-- V30: 给 skill.coverage.analysis 的 5 个 step 显式声明 dependsOn 和 acceptance criteria。
--
-- 背景:
--   1) wave barrier (V16/PlanUpdateTool) 之后,默认串行依赖会让 sector→weak_area→coverage 这条
--      原本可以并行的数据采集链被强行串行,preflight 多 wave 顺序跑完反而比之前慢。
--   2) acceptance criteria (PlanStepRule.Acceptance) 让 step 完成前必须验证字段名/非零值,
--      防止 LLM 把 "cnt_2g=0,cnt_4g=0,cnt_5g=0,cnt_building=0" 这种 region 全空的查询结果
--      当成业务结论塞进报告。
--
-- 拓扑:
--   sector / weak_area / coverage  → 三个并行根 (dependsOn=[])
--   weak_grid                     → 依赖 coverage (复用同一份 SQL,通过 reuseStep)
--   report                        → 依赖前面所有 4 个数据 step,wave barrier
--
-- acceptance 设计:
--   sector / coverage  : requireNonZeroData=true。一个真实 region 不可能扇区/栅格全为 0,
--                       全 0 通常意味着 filter 错(空 city/county、错误 GeoJSON),应当回去
--                       重查或 SKIPPED,不应直接把 0 写进报告。
--   weak_area          : 不强制 requireNonZeroData。某区域真的没有弱覆盖是合法答复(0 是好消息),
--                       只校验 SELECT 列含 total 字段确保 LLM 没把 SELECT 改成无意义的 *。
--   weak_grid          : reuseStep=coverage,沿用 coverage 的校验。
--   report             : artifact-only,acceptance 不适用 (校验靠 reportSection placeholders)。

UPDATE skill_definition
SET config_json = jsonb_set(
        jsonb_set(
            config_json::jsonb,
            '{planStepRules,sector,dependsOn}',
            '[]'::jsonb,
            true
        ),
        '{planStepRules,sector,acceptance}',
        '{"requireNonZeroData": true, "requiredColumns": ["cnt_2g","cnt_4g","cnt_5g","cnt_building"]}'::jsonb,
        true
    )::text
WHERE skill_name = 'skill.coverage.analysis';

UPDATE skill_definition
SET config_json = jsonb_set(
        jsonb_set(
            config_json::jsonb,
            '{planStepRules,weak_area,dependsOn}',
            '[]'::jsonb,
            true
        ),
        '{planStepRules,weak_area,acceptance}',
        '{"requireNonZeroData": false, "requiredColumns": ["total"]}'::jsonb,
        true
    )::text
WHERE skill_name = 'skill.coverage.analysis';

UPDATE skill_definition
SET config_json = jsonb_set(
        jsonb_set(
            config_json::jsonb,
            '{planStepRules,coverage,dependsOn}',
            '[]'::jsonb,
            true
        ),
        '{planStepRules,coverage,acceptance}',
        '{"requireNonZeroData": true, "requiredColumns": ["grid_cnt"]}'::jsonb,
        true
    )::text
WHERE skill_name = 'skill.coverage.analysis';

UPDATE skill_definition
SET config_json = jsonb_set(
        config_json::jsonb,
        '{planStepRules,weak_grid,dependsOn}',
        '["coverage"]'::jsonb,
        true
    )::text
WHERE skill_name = 'skill.coverage.analysis';

UPDATE skill_definition
SET config_json = jsonb_set(
        config_json::jsonb,
        '{planStepRules,report,dependsOn}',
        '["sector","weak_area","coverage","weak_grid"]'::jsonb,
        true
    )::text
WHERE skill_name = 'skill.coverage.analysis';
