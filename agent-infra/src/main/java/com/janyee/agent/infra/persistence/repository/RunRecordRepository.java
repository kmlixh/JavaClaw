package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * 测试专用:绕过 @PreUpdate 直接把 updated_at 写成给定时间。业务路径不应该调用——它存在
     * 仅仅是为了让 StaleRunReconcilerTest 能模拟"一条 run 已经 60 min 没动"。
     */
    @Modifying
    @Query("update RunRecordEntity r set r.updatedAt = :updatedAt where r.id = :runId")
    int forceUpdatedAtForTest(@Param("runId") String runId, @Param("updatedAt") Instant updatedAt);
}
