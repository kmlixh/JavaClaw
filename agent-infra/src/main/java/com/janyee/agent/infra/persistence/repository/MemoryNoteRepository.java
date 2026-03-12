package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryNoteRepository extends JpaRepository<MemoryNoteEntity, Long> {
    List<MemoryNoteEntity> findTop20ByAgentIdOrderByCreatedAtDesc(String agentId);
}
