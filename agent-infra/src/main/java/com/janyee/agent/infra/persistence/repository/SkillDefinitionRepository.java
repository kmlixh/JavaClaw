package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinitionEntity, String> {
    List<SkillDefinitionEntity> findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(String agentId);
}
