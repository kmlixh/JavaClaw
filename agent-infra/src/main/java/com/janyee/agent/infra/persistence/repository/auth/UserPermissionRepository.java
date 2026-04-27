package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.UserPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPermissionRepository extends JpaRepository<UserPermissionEntity, UserPermissionEntity.Pk> {
    List<UserPermissionEntity> findByUserIdAndTenantId(String userId, String tenantId);
}
