package com.janyee.agent.infra.auth;

import java.util.Objects;

/**
 * 多租户资源可见性判定器,统一 5 级 scope。
 *
 * <p>所有可被 scope 的资源(skill / knowledge / tool / llm_provider_config /
 * db_datasource / agent_definition)的列表查询和按 id 取用都走这套规则:</p>
 * <pre>
 *   visible(resource, principal) :=
 *       scope_type == SYSTEM  AND principal.isSystemAdmin()                 → true
 *       scope_type == PUBLIC                                                → true (全员可见)
 *       scope_type == TENANT  AND resource.tenantId == principal.tenantId   → true
 *       scope_type == APP     AND resource.tenantId == principal.tenantId
 *                             AND resource.appId    == principal.appId      → true
 *       scope_type == USER    AND resource.userId   == principal.userId     → true
 *       otherwise                                                           → false
 * </pre>
 *
 * <p>"系统管理员"判定:principal.tenantId == "system"。这是 P1 阶段的简化模型,
 * P2/P3 接入完整 RBAC 后改成检查具体的 admin permission(如 "system.catalog.admin")。</p>
 *
 * <p>API 设计成静态方法,调用方在 stream filter 里直接用,例如:
 * {@code .filter(e -> ResourceScopeFilter.matches(e.getScopeType(), e.getScopeTenantId(),
 *         e.getAppId(), e.getScopeUserId(), principal))}</p>
 */
public final class ResourceScopeFilter {

    public static final String SCOPE_SYSTEM = "SYSTEM";
    public static final String SCOPE_PUBLIC = "PUBLIC";
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_APP    = "APP";
    public static final String SCOPE_USER   = "USER";

    private ResourceScopeFilter() {}

    /** 判定一条资源对当前 principal 是否可见。null scopeType 视为 PUBLIC(向后兼容老数据)。 */
    public static boolean matches(
            String scopeType,
            String scopeTenantId,
            String resourceAppId,
            String scopeUserId,
            AuthPrincipal principal
    ) {
        if (principal == null) return false;
        // 系统管理员对所有 scope 资源全可见 —— 跨租户、跨 app、跨 owner。
        // 之前 SCOPE_TENANT/APP/USER 只比较 ID,sysadmin 的 tenantId='system' 永远跟资源
        // 的 scope_tenant_id='xmap' 等 tenant 对不上,导致 admin 把 skill 改成 TENANT scope
        // 后,系统管理员后台直接看不见。这里短路:isSystemAdmin → true,各 case 内部不用
        // 再各自处理这一例外,语义集中。
        if (isSystemAdmin(principal)) return true;
        String type = scopeType == null || scopeType.isBlank()
                ? SCOPE_PUBLIC
                : scopeType.toUpperCase();
        return switch (type) {
            case SCOPE_PUBLIC -> true;
            case SCOPE_SYSTEM -> false; // 走到这意味着 principal 不是 sysadmin
            case SCOPE_TENANT -> Objects.equals(scopeTenantId, principal.tenantId());
            case SCOPE_APP    -> Objects.equals(scopeTenantId, principal.tenantId())
                                && Objects.equals(resourceAppId, principal.appId());
            case SCOPE_USER   -> Objects.equals(scopeUserId, principal.userId());
            default           -> false;
        };
    }

    /**
     * 兼容老调用点的 4-arg 重载:不传 resourceAppId 时 APP scope 退化成 TENANT 检查。
     * 新代码应该用 5-arg 重载。
     */
    public static boolean matches(
            String scopeType,
            String scopeTenantId,
            String scopeUserId,
            AuthPrincipal principal
    ) {
        return matches(scopeType, scopeTenantId, null, scopeUserId, principal);
    }

    /** 当前线程 principal 的快捷路径。 */
    public static boolean matchesCurrent(
            String scopeType, String scopeTenantId, String resourceAppId, String scopeUserId
    ) {
        return matches(scopeType, scopeTenantId, resourceAppId, scopeUserId,
                SecurityContextHolder.current());
    }

    /** 兼容老调用点的 4-arg 重载。 */
    public static boolean matchesCurrent(String scopeType, String scopeTenantId, String scopeUserId) {
        return matchesCurrent(scopeType, scopeTenantId, null, scopeUserId);
    }

    /** 系统管理员判定:tenantId == "system"。后续接 RBAC 改成 permission 检查。 */
    public static boolean isSystemAdmin(AuthPrincipal principal) {
        return principal != null && Objects.equals("system", principal.tenantId());
    }

    /**
     * 租户管理员判定:principal 在当前租户内有任意 *.manage 权限(skill.manage /
     * knowledge.manage / datasource.manage / llm.manage / user.manage / oauth.client.manage 等)。
     * 这些权限只发给 SUPER_ADMIN 和 TENANT_ADMIN 两类角色,普通 USER 角色不带 .manage 后缀,
     * 所以一个 .manage 命中就足够区分管理员级别。匿名 / 没 token → 不是管理员。
     */
    public static boolean isTenantAdmin(AuthPrincipal principal) {
        if (principal == null || principal.permissions() == null) return false;
        return principal.permissions().stream()
                .anyMatch(p -> p != null && p.endsWith(".manage"));
    }

    /**
     * 编辑权限判定 ——
     *   - 系统管理员(tenantId='system'):任何资源都能编辑;
     *   - 租户管理员(本租户内有 .manage 权限):资源 tenant 跟自己 tenant 一致 → 能编辑;
     *   - 普通用户:只能编辑自己 owner 的资源(scope_user_id == 自己 userId)。
     *
     * 注意:resourceTenantId / resourceUserId 二选一够用,有 tenant 没 user 也合理(TENANT/APP
     * scope 资源没有 owner 字段)。新建场景调用方传入推断后的 owner,这条同样能让本人通过。
     */
    public static boolean canEdit(String resourceTenantId, String resourceUserId, AuthPrincipal principal) {
        if (principal == null) return false;
        if (isSystemAdmin(principal)) return true;
        if (isTenantAdmin(principal)
                && resourceTenantId != null
                && Objects.equals(resourceTenantId, principal.tenantId())) {
            return true;
        }
        return resourceUserId != null
                && !resourceUserId.isBlank()
                && Objects.equals(resourceUserId, principal.userId());
    }

    public static boolean canEditCurrent(String resourceTenantId, String resourceUserId) {
        return canEdit(resourceTenantId, resourceUserId, SecurityContextHolder.current());
    }

    /**
     * 校验 scope 字段组合的合法性。给 admin save/update 入口做请求体校验,
     * 错误返回非 null 的人话错误信息。
     */
    public static String validate(
            String scopeType, String scopeTenantId, String resourceAppId, String scopeUserId
    ) {
        if (scopeType == null || scopeType.isBlank()) {
            return "scope_type 必填";
        }
        String type = scopeType.toUpperCase();
        return switch (type) {
            case SCOPE_SYSTEM, SCOPE_PUBLIC -> null;
            case SCOPE_TENANT -> isBlank(scopeTenantId)
                    ? "TENANT 范围必须填 scope_tenant_id" : null;
            case SCOPE_APP    -> isBlank(scopeTenantId) || isBlank(resourceAppId)
                    ? "APP 范围必须同时填 scope_tenant_id 和 app_id" : null;
            case SCOPE_USER   -> isBlank(scopeUserId)
                    ? "USER 范围必须填 scope_user_id" : null;
            default -> "scope_type 仅支持 SYSTEM / PUBLIC / TENANT / APP / USER,收到: " + scopeType;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
