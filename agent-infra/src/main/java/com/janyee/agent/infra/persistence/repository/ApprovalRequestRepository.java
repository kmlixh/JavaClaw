package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.ApprovalRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, String> {
    boolean existsByRunIdAndToolNameAndStatus(String runId, String toolName, String status);

    List<ApprovalRequestEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<ApprovalRequestEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<ApprovalRequestEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ApprovalRequestEntity> findAllByOrderByCreatedAtDesc();

    void deleteBySessionId(String sessionId);
}
