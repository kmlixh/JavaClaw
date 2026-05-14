package com.janyee.agent.runtime.query;

/**
 * 增强版 session 分页查询的过滤参数包。
 *
 * <p>每个字段为 null / blank 表示该维度不过滤。runStatus 取值约定见
 * {@link AgentQueryService#listSessionsPagedAdvanced(SessionListFilter)}。</p>
 *
 * @param agentId     精确匹配 agent_id;空表示所有 agent
 * @param userId      精确匹配 user_id
 * @param tenantId    精确匹配 tenant_id(非 readAll 用户传了非自己租户的值会被 visibility 覆盖)
 * @param appId       精确匹配 app_id
 * @param runStatus   "running" / "idle" / "all"(或 null/blank,等同 all)
 * @param keyword     title / id / agentId 子串模糊匹配
 * @param page        0-indexed
 * @param size        每页条数,后端会 clamp 到 [1, 200]
 */
public record SessionListFilter(
        String agentId,
        String userId,
        String tenantId,
        String appId,
        String runStatus,
        String keyword,
        int page,
        int size
) {
    public static SessionListFilter of(String agentId, int page, int size, String keyword) {
        return new SessionListFilter(agentId, null, null, null, null, keyword, page, size);
    }
}
