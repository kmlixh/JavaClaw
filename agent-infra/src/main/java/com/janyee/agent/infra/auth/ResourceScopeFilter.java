package com.janyee.agent.infra.auth;

import java.util.Objects;

/**
 * 多租户资源可见性判定器。
 *
 * <p>所有"可被 SYSTEM / TENANT / USER 三层 scope 的资源"(skill / knowledge / memory /
 * datasource / agent)都走这套规则:
 * <pre>
 *   visible(resource, principal) :=
 *       resource.scope == SYSTEM                                    → true
 *       resource.scope == TENANT AND resource.tenantId == ctx.tenant → true
 *       resource.scope == USER   AND resource.userId   == ctx.user   → true
 *       otherwise                                                    → false
 * </pre>
 *
 * <p>agent 是特例:scope 字段叫 visibility,规则本身完全相同 —— 只是字段命名差异,用
 * {@link #matches} 的通用签名覆盖。</p>
 *
 * <p>API 设计成静态方法,调用方直接在 stream filter 里用:
 * {@code .filter(e -> ResourceScopeFilter.matches(e.getScopeType(), e.getScopeTenantId(),
 * e.getScopeUserId(), principal))}</p>
 */
public final class ResourceScopeFilter {

    private ResourceScopeFilter() {}

    /**
     * 判定一条资源对当前 principal 是否可见。null scopeType 一律视为 SYSTEM(向后兼容
     * backfill 前的老行,P1 之后都带 NOT NULL,理论上不再出现 null)。
     */
    public static boolean matches(
            String scopeType,
            String scopeTenantId,
            String scopeUserId,
            AuthPrincipal principal
    ) {
        if (principal == null) return false;
        String type = scopeType == null ? "SYSTEM" : scopeType.toUpperCase();
        return switch (type) {
            case "SYSTEM" -> true;
            case "TENANT" -> Objects.equals(scopeTenantId, principal.tenantId());
            case "USER"   -> Objects.equals(scopeUserId, principal.userId());
            default       -> false;
        };
    }

    /** 当前线程的 principal 快捷路径。 */
    public static boolean matchesCurrent(String scopeType, String scopeTenantId, String scopeUserId) {
        return matches(scopeType, scopeTenantId, scopeUserId, SecurityContextHolder.current());
    }
}
