package com.janyee.agent.infra.session;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.infra.persistence.repository.ArtifactFileRepository;
import com.janyee.agent.infra.persistence.repository.MemoryNoteRepository;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.infra.persistence.repository.ToolAuditLogRepository;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.runtime.session.SessionSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InMemorySessionService implements SessionService {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final RunRecordRepository runRecordRepository;
    private final ToolAuditLogRepository toolAuditLogRepository;
    private final ArtifactFileRepository artifactFileRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final MemoryNoteRepository memoryNoteRepository;

    public InMemorySessionService(
            SessionRepository sessionRepository,
            SessionMessageRepository sessionMessageRepository,
            RunRecordRepository runRecordRepository,
            ToolAuditLogRepository toolAuditLogRepository,
            ArtifactFileRepository artifactFileRepository,
            ApprovalRequestRepository approvalRequestRepository,
            MemoryNoteRepository memoryNoteRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.runRecordRepository = runRecordRepository;
        this.toolAuditLogRepository = toolAuditLogRepository;
        this.artifactFileRepository = artifactFileRepository;
        this.approvalRequestRepository = approvalRequestRepository;
        this.memoryNoteRepository = memoryNoteRepository;
    }

    @Override
    @Transactional
    public SessionSnapshot ensureSession(String sessionId, String agentId, String userId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseGet(() -> createSession(sessionId, agentId, userId));
        return new SessionSnapshot(session.getId(), session.getAgentId(), session.getUserId());
    }

    @Override
    @Transactional
    public SessionSnapshot renameSession(String sessionId, String title) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        String normalized = normalizeTitle(title);
        session.setTitle(normalized);
        SessionEntity saved = sessionRepository.save(session);
        return new SessionSnapshot(saved.getId(), saved.getAgentId(), saved.getUserId());
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        toolAuditLogRepository.deleteBySessionId(sessionId);
        artifactFileRepository.deleteBySessionId(sessionId);
        approvalRequestRepository.deleteBySessionId(sessionId);
        memoryNoteRepository.deleteBySessionId(sessionId);
        sessionMessageRepository.deleteBySessionId(sessionId);
        runRecordRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    private SessionEntity createSession(String sessionId, String agentId, String userId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setAgentId(agentId);
        session.setUserId(userId);
        session.setChannel("web");
        session.setStatus("ACTIVE");
        return sessionRepository.save(session);
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String value = title.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
