package com.janyee.agent.infra.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 ResourceScopeFilter 的可见性矩阵 —— 重点是"系统管理员一票通行"这条不能再被
 * 改回去:之前 admin 把 skill 改成 TENANT scope (scope_tenant_id=xmap) 后,系统管理员
 * (tenantId=system) 后台 list 直接看不见,因为各 case 只比较 ID 没给 sysadmin 留口子。
 */
class ResourceScopeFilterTest {

    private static AuthPrincipal sysadmin() {
        return new AuthPrincipal("admin", "system", "system-default", Set.of(), false);
    }

    private static AuthPrincipal tenantUser(String tenantId, String userId, String appId) {
        return new AuthPrincipal(userId, tenantId, appId, Set.of(), false);
    }

    // ----- 系统管理员一票通行 ---------------------------------------------------

    @Test
    void sysadminSeesAllSystemScope() {
        assertTrue(ResourceScopeFilter.matches("SYSTEM", null, null, null, sysadmin()));
    }

    @Test
    void sysadminSeesPublicScope() {
        assertTrue(ResourceScopeFilter.matches("PUBLIC", null, null, null, sysadmin()));
    }

    @Test
    void sysadminSeesAnotherTenantsTenantScope() {
        // 资源属于 xmap 租户,sysadmin 自己 tenantId=system —— 必须能看见。
        assertTrue(ResourceScopeFilter.matches("TENANT", "xmap", null, null, sysadmin()),
                "系统管理员必须能看见任意租户的 TENANT scope 资源");
    }

    @Test
    void sysadminSeesAnotherTenantsAppScope() {
        assertTrue(ResourceScopeFilter.matches("APP", "xmap", "xmap", null, sysadmin()));
    }

    @Test
    void sysadminSeesOtherUsersUserScope() {
        // 资源 owner 是别人 (ext:xmap:1),sysadmin 自己 userId=admin —— 必须能看见。
        assertTrue(ResourceScopeFilter.matches("USER", null, null, "ext:xmap:1", sysadmin()),
                "系统管理员必须能看见任意用户的 USER scope 资源");
    }

    // ----- 普通租户用户的可见性边界 ---------------------------------------------

    @Test
    void tenantUserCannotSeeSystemScope() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertFalse(ResourceScopeFilter.matches("SYSTEM", null, null, null, user),
                "非系统管理员不应看见 SYSTEM scope 资源");
    }

    @Test
    void tenantUserSeesOwnTenantTenantScope() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.matches("TENANT", "xmap", null, null, user));
    }

    @Test
    void tenantUserCannotSeeOtherTenantTenantScope() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertFalse(ResourceScopeFilter.matches("TENANT", "other-tenant", null, null, user));
    }

    @Test
    void tenantUserSeesPublicScope() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.matches("PUBLIC", null, null, null, user));
    }

    @Test
    void tenantUserAppScopeRequiresBothTenantAndAppMatch() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.matches("APP", "xmap", "xmap", null, user));
        assertFalse(ResourceScopeFilter.matches("APP", "xmap", "other-app", null, user),
                "tenant 对但 app 不对 → 不可见");
        assertFalse(ResourceScopeFilter.matches("APP", "other-tenant", "xmap", null, user),
                "app 对但 tenant 不对 → 不可见");
    }

    @Test
    void tenantUserSeesOnlyOwnUserScope() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.matches("USER", null, null, "ext:xmap:1", user));
        assertFalse(ResourceScopeFilter.matches("USER", null, null, "ext:xmap:2", user),
                "USER scope 资源 owner 不是自己 → 不可见");
    }

    @Test
    void nullScopeFallsBackToPublic() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.matches(null, null, null, null, user),
                "scope_type 为 null 视为 PUBLIC 兼容老数据");
    }

    @Test
    void nullPrincipalIsAlwaysHidden() {
        assertFalse(ResourceScopeFilter.matches("PUBLIC", null, null, null, null));
    }

    // ----- canEdit 复检 -----------------------------------------------------------

    @Test
    void canEditSysadminAllowsCrossTenant() {
        assertTrue(ResourceScopeFilter.canEdit("xmap", null, sysadmin()));
        assertTrue(ResourceScopeFilter.canEdit("other-tenant", "ext:other:9", sysadmin()));
    }

    @Test
    void canEditTenantAdminLimitedToOwnTenant() {
        AuthPrincipal tenantAdmin = new AuthPrincipal(
                "ext:xmap:admin", "xmap", "xmap", Set.of("skill.manage"), false);
        assertTrue(ResourceScopeFilter.canEdit("xmap", null, tenantAdmin));
        assertFalse(ResourceScopeFilter.canEdit("other-tenant", null, tenantAdmin));
    }

    @Test
    void canEditRegularUserLimitedToOwnResources() {
        AuthPrincipal user = tenantUser("xmap", "ext:xmap:1", "xmap");
        assertTrue(ResourceScopeFilter.canEdit("xmap", "ext:xmap:1", user));
        assertFalse(ResourceScopeFilter.canEdit("xmap", "ext:xmap:2", user),
                "普通用户不能编辑别人 owner 的资源");
    }
}
