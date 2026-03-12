package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.ToolAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolAuditLogRepository extends JpaRepository<ToolAuditLogEntity, Long> {
    List<ToolAuditLogEntity> findByRunIdOrderByIdAsc(String runId);
}
