package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RunRecordRepository extends JpaRepository<RunRecordEntity, String> {
    List<RunRecordEntity> findBySessionId(String sessionId);

    Optional<RunRecordEntity> findFirstBySessionIdAndStatusInOrderByUpdatedAtDesc(String sessionId, List<String> statuses);

    List<RunRecordEntity> findByStatusIn(List<String> statuses);

    @Modifying
    @Query("update RunRecordEntity r set r.status = 'FAILED', r.detail = :detail where r.status in :statuses")
    int failRunsByStatusIn(List<String> statuses, String detail);

    void deleteBySessionId(String sessionId);
}
