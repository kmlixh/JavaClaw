package com.janyee.agent.infra.auth;

import java.util.Objects;
import java.util.Set;

/**
 * P3 三档可见性谓词,集中给所有"读 session/run/approval/memory note 等关联资源"的代码用。
 *
 * <ul>
 *   <li><b>readAll</b> → 系统管理员 / anonymous(P2 兼容期):跨租户全可见</li>
 *   <li><b>readTenant</b> → 拥有 {@code session.read.tenant} 权限(租户管理员):
 *       只能看 {@code resourceTenantId == principal.tenantId}</li>
 *   <li><b>readOwn</b> → 默认普通用户:只能看 {@code resourceUserId == principal.userId}</li>
 * </ul>
 *
 * <p>抽出公共类的动机:之前 {@code JpaAgentQueryService} 用一份 private record,
 * {@code JpaApprovalService} 又得抄一份,两边语义偏移就会留权限漏洞。集中到 auth 包
 * 让所有需要"按 session 维度判定可见性"的 service 复用同一份判定。</p>
 */
public final class SessionVisibility {

    private final boolean readAll;
    private final boolean readTenant;
    private final String tenantId;
    private final String userId;

    private SessionVisibility(boolean readAll, boolean readTenant, String tenantId, String userId) {
        this.readAll = readAll;
        this.readTenant = readTenant;
        this.tenantId = tenantId;
        this.userId = userId;
    }

    /** 用 principal 的 permission 集合派生三档可见性。 */
    public static SessionVisibility forPrincipal(AuthPrincipal principal) {
        if (principal == null) {
            return new SessionVisibility(false, false, null, null);
        }
        Set<String> perms = principal.permissions();
        boolean readAll = principal.anonymous() || perms.contains("session.read.all");
        boolean readTenant = readAll || perms.contains("session.read.tenant");
        return new SessionVisibility(readAll, readTenant, principal.tenantId(), principal.userId());
    }

    /** 拉取当前线程 principal 的快捷路径。 */
    public static SessionVisibility forCurrent() {
        return forPrincipal(SecurityContextHolder.current());
    }

    /**
     * 判定"挂在某 tenant + user 上的资源"对当前 principal 是否可读。
     * 读路径(get/list)直接 filter 用,写路径自行抛 SecurityException。
     */
    public boolean canRead(String resourceTenantId, String resourceUserId) {
        if (readAll) return true;
        if (readTenant) return Objects.equals(tenantId, resourceTenantId);
        return Objects.equals(userId, resourceUserId);
    }

    public boolean readAll() { return readAll; }
    public boolean readTenant() { return readTenant; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
}
