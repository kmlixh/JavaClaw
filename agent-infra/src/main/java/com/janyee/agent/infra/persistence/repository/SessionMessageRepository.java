package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    @Query("select coalesce(max(m.seqNo), 0) from SessionMessageEntity m where m.sessionId = :sessionId")
    long findMaxSeqNoBySessionId(String sessionId);

    List<SessionMessageEntity> findBySessionIdOrderBySeqNoAsc(String sessionId);

    Optional<SessionMessageEntity> findFirstByRunIdAndRoleOrderByIdAsc(String runId, String role);
}
