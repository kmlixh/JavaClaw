package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
    List<SessionEntity> findTop20ByOrderByUpdatedAtDesc();

    List<SessionEntity> findTop20ByAgentIdOrderByUpdatedAtDesc(String agentId);

    void deleteById(String id);
}
