package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.UserTenantRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserTenantRoleRepository extends JpaRepository<UserTenantRoleEntity, UserTenantRoleEntity.Pk> {
    List<UserTenantRoleEntity> findByUserId(String userId);
    List<UserTenantRoleEntity> findByUserIdAndTenantId(String userId, String tenantId);
    List<UserTenantRoleEntity> findByTenantId(String tenantId);
}
