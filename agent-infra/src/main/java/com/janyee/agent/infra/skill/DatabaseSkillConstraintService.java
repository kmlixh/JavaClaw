package com.janyee.agent.infra.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.runtime.skill.PlanStepRule;
import com.janyee.agent.runtime.skill.SkillConstraint;
import com.janyee.agent.runtime.skill.SkillConstraintService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the `config_json` column on every enabled skill definition for an agent and
 * exposes the structured constraints (`whitelistTables`, `planStepIds`, `strict`).
 * Invalid JSON or missing fields degrade to an empty constraint so a corrupted skill
 * cannot accidentally turn off enforcement for its siblings.
 */
@Service
public class DatabaseSkillConstraintService implements SkillConstraintService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSkillConstraintService.class);

    private final SkillDefinitionRepository repository;
    private final ObjectMapper objectMapper;

    public DatabaseSkillConstraintService(
            SkillDefinitionRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SkillConstraint> listActive(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        List<SkillDefinitionEntity> entities =
                repository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId);
        List<SkillConstraint> constraints = new ArrayList<>(entities.size());
        for (SkillDefinitionEntity entity : entities) {
            constraints.add(parse(entity));
        }
        return constraints;
    }

    private SkillConstraint parse(SkillDefinitionEntity entity) {
        List<String> triggers = parseTriggerKeywords(entity.getTriggerKeywords());
        String raw = entity.getConfigJson();
        if (raw == null || raw.isBlank()) {
            return new SkillConstraint(entity.getSkillName(), List.of(), List.of(), Map.of(), false, triggers);
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean strict = node.path("strict").asBoolean(false);
            List<String> whitelist = readStringArray(node.path("whitelistTables"));
            List<String> planStepIds = readStringArray(node.path("planStepIds"));
            Map<String, PlanStepRule> stepRules = readStepRules(node.path("planStepRules"));
            return new SkillConstraint(entity.getSkillName(), whitelist, planStepIds, stepRules, strict, triggers);
        } catch (Exception error) {
            log.warn("skill.constraint.parse_failed skillName={}, error={}",
                    entity.getSkillName(), error.getMessage());
            return new SkillConstraint(entity.getSkillName(), List.of(), List.of(), Map.of(), false, triggers);
        }
    }

    private List<String> parseTriggerKeywords(String triggersJson) {
        if (triggersJson == null || triggersJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(triggersJson);
            return readStringArray(node);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, PlanStepRule> readStepRules(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Map.of();
        }
        Map<String, PlanStepRule> rules = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String stepId = entry.getKey();
            if (stepId == null || stepId.isBlank()) {
                continue;
            }
            JsonNode ruleNode = entry.getValue();
            if (ruleNode == null || !ruleNode.isObject()) {
                continue;
            }
            // dependsOn 必须区分"skill 没声明"和"skill 声明了空数组":前者走默认串行兜底,
            // 后者明确表示"我是根 step,无前置"。用 has() 判断 key 是否存在。
            boolean dependsOnDeclared = ruleNode.has("dependsOn");
            List<String> dependsOnList = dependsOnDeclared
                    ? readStringArray(ruleNode.path("dependsOn"))
                    : List.of();
            PlanStepRule.Acceptance acceptance = readAcceptance(ruleNode.path("acceptance"));
            rules.put(stepId.trim(), new PlanStepRule(
                    readStringArray(ruleNode.path("requiresSuccess")),
                    readStringArray(ruleNode.path("tableAllowList")),
                    ruleNode.path("minQueries").asInt(0),
                    ruleNode.path("zeroRowsAllowed").asBoolean(true),
                    readStringArray(ruleNode.path("mustMatchTemplateAnchors")),
                    ruleNode.path("reuseStep").asText(""),
                    readStringArray(ruleNode.path("sqlTemplates")),
                    readStringArray(ruleNode.path("sqlTemplatesGeoJson")),
                    readStringArray(ruleNode.path("sqlTemplatesNoFilter")),
                    readReportSection(ruleNode.path("reportSection")),
                    ruleNode.path("jdbcUrl").asText(""),
                    readStringArray(ruleNode.path("requiredTables")),
                    dependsOnList,
                    dependsOnDeclared,
                    acceptance
            ));
        }
        return rules;
    }

    private PlanStepRule.Acceptance readAcceptance(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return PlanStepRule.Acceptance.NONE;
        }
        List<String> requiredColumns = readStringArray(node.path("requiredColumns"));
        boolean requireNonZero = node.path("requireNonZeroData").asBoolean(false);
        if (requiredColumns.isEmpty() && !requireNonZero) {
            return PlanStepRule.Acceptance.NONE;
        }
        return new PlanStepRule.Acceptance(requiredColumns, requireNonZero);
    }

    private PlanStepRule.ReportSection readReportSection(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        String heading = node.path("heading").asText("").trim();
        JsonNode placeholdersNode = node.path("placeholders");
        List<PlanStepRule.Placeholder> placeholders = new ArrayList<>();
        if (placeholdersNode.isArray()) {
            for (JsonNode item : placeholdersNode) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String label = item.path("label").asText("").trim();
                String source = item.path("source").asText("").trim();
                if (!label.isEmpty() || !source.isEmpty()) {
                    placeholders.add(new PlanStepRule.Placeholder(label, source));
                }
            }
        }
        if (heading.isEmpty() && placeholders.isEmpty()) {
            return null;
        }
        return new PlanStepRule.ReportSection(heading, placeholders);
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        return values;
    }
}
