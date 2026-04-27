package com.janyee.agent.infra.admin;

import com.janyee.agent.infra.persistence.entity.auth.RoleEntity;
import com.janyee.agent.infra.persistence.entity.auth.RolePermissionEntity;
import com.janyee.agent.infra.persistence.entity.auth.TenantMenuEntity;
import com.janyee.agent.infra.persistence.repository.auth.RolePermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.RoleRepository;
import com.janyee.agent.infra.persistence.repository.auth.TenantMenuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 新租户初始化:自动建好两个标准角色(TENANT_ADMIN / USER)+ 默认开通的菜单。
 *
 * <p>每次保存(创建)租户时调一次。已经存在的角色/菜单 ON CONFLICT 跳过 ——
 * 重跑(老租户也跑一次)是幂等的,可以补齐之前缺失的模板项。</p>
 *
 * <p>权限模板写死在这里,简单直接。如果未来要让运维"自定义某个租户的默认 USER 权限",
 * 把 DEFAULT_USER_PERMS / DEFAULT_TENANT_ADMIN_PERMS 改成读 DB 的某张 template 表即可。</p>
 */
@Service
public class TenantBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrapService.class);

    /** 普通用户权限模板:能用 chat,管理自己私有的 skill / knowledge / memory / datasource。 */
    public static final List<String> DEFAULT_USER_PERMS = List.of(
            "menu.chat",
            "menu.agents",
            "menu.skills",
            "menu.knowledge",
            "menu.memory",
            "menu.datasources",
            "menu.approvals",
            "menu.search",
            "agent.read",
            "skill.manage",
            "knowledge.manage",
            "memory.manage",
            "datasource.manage",
            "session.read.own",
            "session.terminate"
    );

    /** 租户管理员权限模板:能管本租户用户、看本租户所有会话、管本租户 OAuth 客户端。 */
    public static final List<String> DEFAULT_TENANT_ADMIN_PERMS = List.of(
            "menu.chat",
            "menu.agents",
            "menu.skills",
            "menu.knowledge",
            "menu.memory",
            "menu.datasources",
            "menu.approvals",
            "menu.search",
            "menu.users",
            "menu.oauth-clients",
            "agent.read",
            "agent.edit",
            "agent.bind.user",
            "skill.manage",
            "knowledge.manage",
            "memory.manage",
            "datasource.manage",
            "session.read.tenant",
            "session.terminate",
            "user.manage",
            "oauth.client.manage"
    );

    /** 默认开通的菜单(顺序就是 sort_order)。SUPER_ADMIN 专属菜单(tenants / roles)留给 system 租户。 */
    public static final List<String> DEFAULT_MENUS = List.of(
            "chat", "agents", "skills", "knowledge", "memory",
            "datasources", "approvals", "search", "users", "oauth-clients"
    );

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final TenantMenuRepository tenantMenuRepository;

    public TenantBootstrapService(
            RoleRepository roleRepository,
            RolePermissionRepository rolePermissionRepository,
            TenantMenuRepository tenantMenuRepository
    ) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.tenantMenuRepository = tenantMenuRepository;
    }

    @Transactional
    public void bootstrap(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        ensureRole(tenantId, tenantId + "-user",
                "USER", tenantId + " 普通用户",
                "Auto-provisioned standard user role for tenant " + tenantId,
                DEFAULT_USER_PERMS);
        ensureRole(tenantId, tenantId + "-tenant-admin",
                "TENANT_ADMIN", tenantId + " 租户管理员",
                "Auto-provisioned tenant-admin role for tenant " + tenantId,
                DEFAULT_TENANT_ADMIN_PERMS);
        ensureMenus(tenantId, DEFAULT_MENUS);
        log.info("tenant.bootstrap.completed tenantId={}", tenantId);
    }

    private void ensureRole(String tenantId, String roleId, String code, String name,
                             String description, List<String> permissions) {
        RoleEntity existing = roleRepository.findByTenantIdAndCode(tenantId, code).orElse(null);
        if (existing == null) {
            RoleEntity role = new RoleEntity();
            role.setId(roleId);
            role.setTenantId(tenantId);
            role.setCode(code);
            role.setName(name);
            role.setDescription(description);
            existing = roleRepository.save(role);
        }
        // 权限补齐(已有的不动,新加的进入)
        java.util.Set<String> already = new java.util.HashSet<>();
        for (RolePermissionEntity rp : rolePermissionRepository.findByRoleId(existing.getId())) {
            already.add(rp.getPermissionCode());
        }
        for (String code1 : permissions) {
            if (already.contains(code1)) continue;
            RolePermissionEntity rp = new RolePermissionEntity();
            rp.setRoleId(existing.getId());
            rp.setPermissionCode(code1);
            rolePermissionRepository.save(rp);
        }
    }

    private void ensureMenus(String tenantId, List<String> menuCodes) {
        for (int i = 0; i < menuCodes.size(); i++) {
            String code = menuCodes.get(i);
            TenantMenuEntity.Pk pk = new TenantMenuEntity.Pk(tenantId, code);
            if (tenantMenuRepository.existsById(pk)) continue;
            TenantMenuEntity menu = new TenantMenuEntity();
            menu.setTenantId(tenantId);
            menu.setMenuCode(code);
            menu.setEnabled(true);
            menu.setSortOrder(i + 1);
            tenantMenuRepository.save(menu);
        }
    }
}
