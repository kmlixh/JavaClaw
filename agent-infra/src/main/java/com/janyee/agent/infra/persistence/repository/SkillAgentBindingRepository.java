package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SkillAgentBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillAgentBindingRepository
        extends JpaRepository<SkillAgentBindingEntity, SkillAgentBindingEntity.PK> {

    List<SkillAgentBindingEntity> findByAgentId(String agentId);

    List<SkillAgentBindingEntity> findBySkillId(String skillId);

    void deleteBySkillId(String skillId);
}
