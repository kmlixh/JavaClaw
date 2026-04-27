package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.entity.auth.UserPermissionEntity;
import com.janyee.agent.infra.persistence.entity.auth.UserTenantRoleEntity;
import com.janyee.agent.infra.persistence.repository.auth.RolePermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserPermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserTenantRoleRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析用户在某租户内的有效权限集合。
 *
 * <p>判定顺序:
 * <pre>
 *   1. 拉 user_tenant_role(user, tenant) 的所有 roles
 *   2. 并所有 role_permission → basePerms
 *   3. 加 user_permission(user, tenant, effect=GRANT) → 扩充
 *   4. 去 user_permission(user, tenant, effect=DENY)  → 强制拒(DENY 优先)
 * </pre>
 *
 * <p>调用方:JWT 签发时拍快照塞进 AuthPrincipal,后续每个请求都靠 token 里的 principal。
 * 不是实时查 —— 避免每个 request 都命中 DB。</p>
 */
@Service
public class PermissionResolver {

    private final UserTenantRoleRepository userTenantRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionRepository userPermissionRepository;

    public PermissionResolver(
            UserTenantRoleRepository userTenantRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            UserPermissionRepository userPermissionRepository
    ) {
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userPermissionRepository = userPermissionRepository;
    }

    public Set<String> resolve(String userId, String tenantId) {
        if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Set.of();
        }
        List<UserTenantRoleEntity> bindings = userTenantRoleRepository
                .findByUserIdAndTenantId(userId, tenantId);
        List<String> roleIds = bindings.stream().map(UserTenantRoleEntity::getRoleId).toList();
        Set<String> permissions = new LinkedHashSet<>();
        if (!roleIds.isEmpty()) {
            rolePermissionRepository.findByRoleIdIn(roleIds)
                    .forEach(rp -> permissions.add(rp.getPermissionCode()));
        }
        List<UserPermissionEntity> overrides = userPermissionRepository
                .findByUserIdAndTenantId(userId, tenantId);
        for (UserPermissionEntity o : overrides) {
            if ("GRANT".equalsIgnoreCase(o.getEffect())) {
                permissions.add(o.getPermissionCode());
            } else if ("DENY".equalsIgnoreCase(o.getEffect())) {
                permissions.remove(o.getPermissionCode());
            }
        }
        return Collections.unmodifiableSet(permissions);
    }
}
