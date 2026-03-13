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

    Optional<SessionMessageEntity> findFirstBySessionIdAndRoleOrderBySeqNoAsc(String sessionId, String role);

    Optional<SessionMessageEntity> findFirstByRunIdAndRoleOrderByIdAsc(String runId, String role);

    void deleteBySessionId(String sessionId);

    @Query("""
            select m from SessionMessageEntity m
            where (:sessionId is null or m.sessionId = :sessionId)
              and lower(m.content) like lower(concat('%', :query, '%'))
            order by m.createdAt desc
            """)
    List<SessionMessageEntity> searchByContent(String query, String sessionId);
}
