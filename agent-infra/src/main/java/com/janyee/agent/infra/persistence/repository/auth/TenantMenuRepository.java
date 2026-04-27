package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.TenantMenuEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantMenuRepository extends JpaRepository<TenantMenuEntity, TenantMenuEntity.Pk> {
    List<TenantMenuEntity> findByTenantIdOrderBySortOrderAsc(String tenantId);
}
