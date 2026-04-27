package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<PermissionEntity, String> {
}
