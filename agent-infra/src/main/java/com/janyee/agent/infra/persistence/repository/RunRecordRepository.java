package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRecordRepository extends JpaRepository<RunRecordEntity, String> {
    List<RunRecordEntity> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
