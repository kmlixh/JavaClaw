package com.janyee.agent.api;

import java.util.List;

/**
 * @param agentId  legacy 字段。V19+ 应填 {@link #agentIds()};agentId 仅在 agentIds 为 null/空
 *                 时作为单元素退回使用,用于向后兼容旧 client。
 * @param agentIds M:N 关系的权威来源:该 skill 要绑给哪些 agent。保存时 JpaAdminCatalogService
 *                 会用它替换 skill_agent_binding 的全部行。
 * @param triggerKeywords 可选 JSON 数组字符串,例如 {@code ["覆盖分析","扇区统计"]}。命中任一词时
 *                 才激活此 skill 的 whitelist/planStepIds 约束;空则 legacy 永远激活。
 */
public record SkillDefinitionRequest(
        String id,
        String agentId,
        List<String> agentIds,
        String skillName,
        String description,
        String promptTemplate,
        String configJson,
        String triggerKeywords,
        boolean enabled,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
