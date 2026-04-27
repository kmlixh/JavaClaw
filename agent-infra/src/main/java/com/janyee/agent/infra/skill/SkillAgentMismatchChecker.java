package com.janyee.agent.infra.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.persistence.entity.SkillAgentBindingEntity;
import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.SkillAgentBindingRepository;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Plan B bolt-on: early warning when the user's message matches a skill's trigger keywords
 * but that skill isn't bound to the current agent.
 *
 * <p>Symptom this prevents: user picks {@code ops-agent}, types "给出盘龙区覆盖分析", and
 * since {@code skill.coverage.analysis} is only bound to {@code dev-agent} the LLM has no
 * plan/whitelist/skill prompt to guide it. It spends 30 iterations bouncing between file.list
 * and db.schema.inspect before giving up. The user blames the LLM when the real problem is
 * agent routing.</p>
 *
 * <p>Detection: any skill with non-empty {@code trigger_keywords} whose keywords appear
 * literally in the user message. Returns {@link MismatchResult} with
 * {@code {skillName, ownerAgentIds, matchedKeyword}} or empty when OK.</p>
 */
@Component
public class SkillAgentMismatchChecker {

    private static final Logger log = LoggerFactory.getLogger(SkillAgentMismatchChecker.class);

    private final SkillDefinitionRepository skillRepository;
    private final SkillAgentBindingRepository bindingRepository;
    private final ObjectMapper objectMapper;

    public SkillAgentMismatchChecker(
            SkillDefinitionRepository skillRepository,
            SkillAgentBindingRepository bindingRepository,
            ObjectMapper objectMapper
    ) {
        this.skillRepository = skillRepository;
        this.bindingRepository = bindingRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @return {@link Optional#empty()} if no mismatch (either no skill has triggers matching
     *         the message, or the current agent already owns the matching skill).
     */
    public Optional<MismatchResult> detect(String agentId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        String lowerMessage = userMessage.toLowerCase(Locale.ROOT);
        List<SkillDefinitionEntity> triggerCandidates;
        try {
            triggerCandidates = skillRepository.findByEnabledTrueAndTriggerKeywordsIsNotNull();
        } catch (Exception error) {
            log.warn("skill.mismatch.lookup_failed error={}", error.getMessage());
            return Optional.empty();
        }
        if (triggerCandidates.isEmpty()) {
            return Optional.empty();
        }

        // skillName -> (ownerAgentIds, matchedKeyword). V19 起"owner agent"来自 binding 表,
        // 一个 skill 可能绑多个 agent,全部返回给用户做切换提示。
        java.util.Map<String, SkillOwnership> byName = new java.util.LinkedHashMap<>();
        for (SkillDefinitionEntity entity : triggerCandidates) {
            if (!entity.isEnabled()) continue;
            List<String> keywords = parseKeywords(entity.getTriggerKeywords());
            String matched = firstMatch(lowerMessage, keywords);
            if (matched == null) continue;
            SkillOwnership ownership = byName.computeIfAbsent(entity.getSkillName(),
                    name -> new SkillOwnership(name, new LinkedHashSet<>(), matched));
            for (SkillAgentBindingEntity binding : bindingRepository.findBySkillId(entity.getId())) {
                ownership.agentIds().add(binding.getAgentId());
            }
        }

        for (SkillOwnership ownership : byName.values()) {
            if (ownership.agentIds().contains(agentId)) {
                // current agent already owns a matching skill — no mismatch
                return Optional.empty();
            }
            // Report the first mismatch; if multiple skills match, one error is enough to
            // prompt the user to switch agent.
            return Optional.of(new MismatchResult(
                    ownership.skillName(),
                    List.copyOf(ownership.agentIds()),
                    ownership.matchedKeyword()));
        }
        return Optional.empty();
    }

    private List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    String text = item.asText().trim();
                    if (!text.isBlank()) result.add(text.toLowerCase(Locale.ROOT));
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String firstMatch(String lowerMessage, List<String> keywords) {
        for (String kw : keywords) {
            if (lowerMessage.contains(kw)) return kw;
        }
        return null;
    }

    /**
     * @param skillName name of the skill the user's message triggered
     * @param ownerAgentIds all agents that currently have that skill bound
     * @param matchedKeyword the keyword from trigger_keywords that matched the message
     */
    public record MismatchResult(String skillName, List<String> ownerAgentIds, String matchedKeyword) {
        public String friendlyMessage(String currentAgentId) {
            return "当前 agent \"" + currentAgentId + "\" 未挂载 skill \"" + skillName
                    + "\"（因为消息中出现了关键词 \"" + matchedKeyword + "\"）。"
                    + " 该 skill 已绑定到：" + ownerAgentIds
                    + "。请切换到上述 agent 之一再发送此请求，否则 LLM 缺少规划和白名单约束，"
                    + "极有可能在无头尝试中浪费大量迭代。";
        }
    }

    private record SkillOwnership(String skillName, Set<String> agentIds, String matchedKeyword) {}
}
