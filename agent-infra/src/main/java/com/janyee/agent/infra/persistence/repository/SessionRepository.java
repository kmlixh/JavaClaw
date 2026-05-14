package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String>,
        JpaSpecificationExecutor<SessionEntity> {
    List<SessionEntity> findTop20ByOrderByUpdatedAtDesc();

    List<SessionEntity> findTop20ByAgentIdOrderByUpdatedAtDesc(String agentId);

    /** 真分页变体:按 updatedAt desc 排序,接受任意 PageRequest(page + size)。 */
    List<SessionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    List<SessionEntity> findByAgentIdOrderByUpdatedAtDesc(String agentId, Pageable pageable);

    void deleteById(String id);
}
