package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.AgentLabIterationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentLabIterationRepository extends JpaRepository<AgentLabIterationEntity, Long> {

    List<AgentLabIterationEntity> findByTaskIdOrderByIterationNoAsc(String taskId);

    long countByTaskId(String taskId);
}
