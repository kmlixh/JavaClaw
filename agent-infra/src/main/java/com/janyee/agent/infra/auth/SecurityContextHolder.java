package com.janyee.agent.infra.auth;

/**
 * 线程内的当前 AuthPrincipal。
 *
 * <p>P1 阶段:请求进来时未设置 principal 会默认返回 {@link AuthPrincipal#anonymousSystemAdmin()},
 * 对应 V24 seed 的 admin 用户 + SYSTEM 租户 —— 现有匿名路径透明地落到系统默认身份,不破坏行为。</p>
 *
 * <p>P2 阶段:`JwtAuthFilter` 解 token 后 `setCurrent(...)`,请求结束 `clear()`。
 * 由 Spring 自己的 SecurityContext 或我们自己装的 Filter 维护,和本 holder 二选一。</p>
 */
public final class SecurityContextHolder {

    private static final ThreadLocal<AuthPrincipal> CURRENT = new ThreadLocal<>();

    private SecurityContextHolder() {}

    public static AuthPrincipal current() {
        AuthPrincipal p = CURRENT.get();
        return p != null ? p : AuthPrincipal.anonymousSystemAdmin();
    }

    public static void setCurrent(AuthPrincipal principal) {
        CURRENT.set(principal);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
