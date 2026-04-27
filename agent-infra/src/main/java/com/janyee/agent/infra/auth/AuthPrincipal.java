package com.janyee.agent.infra.auth;

import java.util.Set;

/**
 * 当前请求的认证主体。P1 阶段:匿名请求 → 默认 admin + SYSTEM 租户;
 * P2 阶段:JWT 过滤器解 token 填进来。
 */
public record AuthPrincipal(
        String userId,
        String tenantId,
        String appId,
        Set<String> permissions,
        boolean anonymous
) {
    /** P1 匿名请求用的常量 principal —— admin@system,全量权限。 */
    public static AuthPrincipal anonymousSystemAdmin() {
        return new AuthPrincipal(
                "admin",
                "system",
                "system-default",
                Set.of(),  // 权限集暂时留空,P2 由 PermissionResolver 解析后填
                true
        );
    }
}
