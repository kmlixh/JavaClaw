package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.AgentLabTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentLabTaskRepository extends JpaRepository<AgentLabTaskEntity, String> {

    List<AgentLabTaskEntity> findAllByOrderByCreatedAtDesc();

    List<AgentLabTaskEntity> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);
}
