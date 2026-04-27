package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionEntity.Pk> {
    List<RolePermissionEntity> findByRoleId(String roleId);
    List<RolePermissionEntity> findByRoleIdIn(List<String> roleIds);
}
