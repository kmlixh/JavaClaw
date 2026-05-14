package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.RunEventStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RunEventStepRepository extends JpaRepository<RunEventStepEntity, Long> {

    List<RunEventStepEntity> findByRunIdOrderBySeqAsc(String runId);

    /** 分类过滤,前端 chip 命中后的快速服务端查询。空集合 = 不查。 */
    List<RunEventStepEntity> findByRunIdAndCategoryInOrderBySeqAsc(String runId, Collection<String> categories);

    long countByRunId(String runId);

    @Modifying
    @Query("DELETE FROM RunEventStepEntity e WHERE e.runId = :runId")
    int deleteByRunId(@Param("runId") String runId);
}
