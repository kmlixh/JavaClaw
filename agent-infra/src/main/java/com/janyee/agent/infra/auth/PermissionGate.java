package com.janyee.agent.infra.auth;

/**
 * 写操作权限断言。读操作走 scope filter,写操作用这里。
 *
 * <pre>
 *   require("llm.manage")
 *     → 当前 principal 必须在 permissions 里有 "llm.manage"
 *     → 匿名期(anonymous=true)一律放行,保持 P1/P2 老行为
 *     → 缺权限抛 AuthService.AuthException("forbidden", "missing permission: ...")
 * </pre>
 *
 * <p>P4 把 anonymous 关掉以后,所有写入都必须带合法 JWT,这里的 require 就开始真正起作用。</p>
 */
public final class PermissionGate {

    private PermissionGate() {}

    public static void require(String permissionCode) {
        require(SecurityContextHolder.current(), permissionCode);
    }

    public static void require(AuthPrincipal principal, String permissionCode) {
        if (principal == null || permissionCode == null || permissionCode.isBlank()) {
            throw new AuthService.AuthException("forbidden", "missing principal or permission code");
        }
        if (principal.anonymous()) return;
        if (!principal.permissions().contains(permissionCode)) {
            throw new AuthService.AuthException("forbidden", "missing permission: " + permissionCode);
        }
    }
}
