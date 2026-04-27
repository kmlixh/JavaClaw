package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, String> {
    List<RoleEntity> findByTenantId(String tenantId);
    Optional<RoleEntity> findByTenantIdAndCode(String tenantId, String code);
}
