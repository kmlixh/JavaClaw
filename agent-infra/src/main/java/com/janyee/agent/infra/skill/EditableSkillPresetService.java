package com.janyee.agent.infra.skill;

import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.runtime.skill.SkillPrompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class EditableSkillPresetService {

    public static final String DB_SCHEMA_SKILL_NAME = "skill.db.schema.inspect";

    private static final SkillPrompt DB_SCHEMA_SKILL = new SkillPrompt(
            DB_SCHEMA_SKILL_NAME,
            "高效数据库查询技能：最少探测、一次确认、直接产出结果。",
            """
            你是数据库查询执行助手，目标是“最少工具调用拿到最终结果”，禁止无效重复探测。

            【效率优先规则】
            1. 已知表名、字段名、过滤条件时，直接执行业务 SQL；不要先查表结构。
            2. 每张表最多只允许 1 次 schema 探测；一旦某张表探测成功，后续必须复用该表 schema。
            3. 同一张表严禁连续或重复探测；不同表缺少字段信息时，允许对缺失表各调用一次 db.schema.inspect。
            4. 同一条业务 SQL 不得重复执行；业务 SQL 成功后按任务性质决定下一步：
               - 用户要图表 → 在 artifact.markdown 正文里手写 ```echarts\n{ECharts option JSON}\n``` 代码块，前端 MarkdownBlock 会渲染；没有独立的图表渲染工具。
               - 用户要表格 → 在 artifact.markdown 正文里手写 GFM 表格；没有表格渲染工具。
               - 用户只要答案 → 直接回复或进入下一 step。
               严禁把 schema 探测的结果当成最终交付内容。

            【结构探测工具约束】
            5. 结构探测必须使用专用工具 db.schema.inspect。
            6. 禁止用 db.query 执行 DESCRIBE、SHOW COLUMNS、information_schema.columns、pg_catalog.pg_attribute、SELECT * LIMIT 1 来获取表结构。
            7. db.schema.inspect 参数只传 table/schema（以及 jdbcUrl/username/password 或其他连接参数），一次返回完整列信息后直接复用。
            8. 如果不知道表是否存在，可以用 db.query 查询 information_schema.tables 进行表名发现；找到具体表后仍必须用 db.schema.inspect 获取字段。

            【错误恢复规则】
            9. SQL 报错后先依据错误信息修正当前 SQL，优先修正列名/表名。
            10. 若错误涉及未知列或未知表，且当前 schema context 没有覆盖该表，调用 db.schema.inspect 检查该具体表。
            11. 常见列名以真实表结构为准，例如 used_freq/longitude/latitude；不要臆造 usage_freq、usage_frequency、type_name、remarks 等字段。

            【地图统计任务规则】
            12. 地图区域统计优先使用附件中的 geometry（GeoJSON, SRID=4326），不要退化为 bbox。
                PostgreSQL/PostGIS 推荐写法：
                ST_Intersects(
                  ST_SetSRID(ST_MakePoint(longitude, latitude), 4326),
                  ST_SetSRID(ST_GeomFromGeoJSON('<geometry_json>'), 4326)
                )
                多个区域可用 OR 连接多个 ST_Intersects 条件。
            13. 聚合查询完成后，按用户要求输出：
                - 要"图表" => 直接在 artifact.markdown 里手写 ```echarts\n{ECharts option JSON}\n``` 代码块，不调任何工具
                - 要"表格" => 直接在 artifact.markdown 里手写 GFM 表格，不调任何工具
                - 严禁把 schema 探测 / 列名排查 / debug peek 的结果当最终交付内容输出。

            【终止条件】
            14. 用户想要的结果已拿到（最终渲染成功或最终答复给出）立即结束本轮，不再追加任何 schema/db.query 调用。
            """
    );

    private final SkillDefinitionRepository repository;

    public EditableSkillPresetService(SkillDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void ensurePresetSkills(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        repository.findByAgentIdAndSkillName(agentId, DB_SCHEMA_SKILL_NAME)
                .ifPresentOrElse(existing -> {
                    existing.setDescription(DB_SCHEMA_SKILL.description());
                    existing.setPromptTemplate(DB_SCHEMA_SKILL.promptTemplate());
                    existing.setConfigJson("{\"preset\":true}");
                    existing.setEnabled(true);
                    existing.setVersion(existing.getVersion() + 1);
                    repository.save(existing);
                }, () -> repository.save(newPresetEntity(agentId, DB_SCHEMA_SKILL)));
    }

    public List<SkillPrompt> presetSkillPrompts() {
        return List.of(DB_SCHEMA_SKILL);
    }

    private SkillDefinitionEntity newPresetEntity(String agentId, SkillPrompt skill) {
        SkillDefinitionEntity entity = new SkillDefinitionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setAgentId(agentId);
        entity.setSkillName(skill.skillName());
        entity.setDescription(skill.description());
        entity.setPromptTemplate(skill.promptTemplate());
        entity.setConfigJson("{\"preset\":true}");
        entity.setEnabled(true);
        entity.setVersion(1);
        return entity;
    }
}
