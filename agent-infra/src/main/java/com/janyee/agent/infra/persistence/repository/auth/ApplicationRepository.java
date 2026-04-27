package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.ApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, String> {
    List<ApplicationEntity> findByTenantId(String tenantId);
    Optional<ApplicationEntity> findByTenantIdAndCode(String tenantId, String code);
}
