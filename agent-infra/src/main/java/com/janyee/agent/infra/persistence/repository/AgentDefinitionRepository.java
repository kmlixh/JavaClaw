package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {
    List<AgentDefinitionEntity> findAllByOrderByDisplayNameAsc();

    List<AgentDefinitionEntity> findByEnabledTrueOrderByDisplayNameAsc();
}
