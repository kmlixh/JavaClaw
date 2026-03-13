package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.ArtifactFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactFileRepository extends JpaRepository<ArtifactFileEntity, Long> {
    List<ArtifactFileEntity> findByRunIdOrderByIdAsc(String runId);

    List<ArtifactFileEntity> findTop20ByRunIdAndNameContainingIgnoreCaseOrderByIdDesc(String runId, String name);

    void deleteBySessionId(String sessionId);
}
