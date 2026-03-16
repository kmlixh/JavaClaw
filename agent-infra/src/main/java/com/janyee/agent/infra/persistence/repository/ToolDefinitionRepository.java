package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.ToolDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolDefinitionRepository extends JpaRepository<ToolDefinitionEntity, String> {
    List<ToolDefinitionEntity> findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(String agentId);
}
