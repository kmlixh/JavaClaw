package com.janyee.agent.runtime.admin;

import java.util.List;

/**
 * @param agentId         legacy 1:N 字段,仅当 {@link #agentIds()} 为空时兜底使用。
 * @param agentIds        M:N 绑定的权威 agent 列表;保存时会重置 {@code skill_agent_binding}。
 * @param triggerKeywords 可选,JSON 数组字符串。
 */
public record SkillDefinitionCommand(
        String id,
        String agentId,
        List<String> agentIds,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        String triggerKeywords,
        boolean enabled
) {
}
