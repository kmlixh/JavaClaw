package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.ApprovalRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, String> {
    boolean existsByRunIdAndToolNameAndStatus(String runId, String toolName, String status);

    void deleteBySessionId(String sessionId);
}
