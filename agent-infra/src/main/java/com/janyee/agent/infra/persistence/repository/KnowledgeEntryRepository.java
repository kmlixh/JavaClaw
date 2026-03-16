package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.KnowledgeEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, String> {
    List<KnowledgeEntryEntity> findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(String agentId);
}
