package com.janyee.agent.infra.query;

import com.janyee.agent.infra.persistence.entity.ArtifactFileEntity;
import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.entity.ToolAuditLogEntity;
import com.janyee.agent.infra.persistence.repository.ArtifactFileRepository;
import com.janyee.agent.infra.persistence.repository.MemoryNoteRepository;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.infra.persistence.repository.ToolAuditLogRepository;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.ArtifactView;
import com.janyee.agent.runtime.query.SessionSummaryView;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.MemoryNoteView;
import com.janyee.agent.runtime.query.SessionDetailView;
import com.janyee.agent.runtime.query.SessionMessageView;
import com.janyee.agent.runtime.query.ToolAuditLogView;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JpaAgentQueryService implements AgentQueryService {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final RunRecordRepository runRecordRepository;
    private final ToolAuditLogRepository toolAuditLogRepository;
    private final ArtifactFileRepository artifactFileRepository;
    private final MemoryNoteRepository memoryNoteRepository;

    public JpaAgentQueryService(
            SessionRepository sessionRepository,
            SessionMessageRepository sessionMessageRepository,
            RunRecordRepository runRecordRepository,
            ToolAuditLogRepository toolAuditLogRepository,
            ArtifactFileRepository artifactFileRepository,
            MemoryNoteRepository memoryNoteRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.runRecordRepository = runRecordRepository;
        this.toolAuditLogRepository = toolAuditLogRepository;
        this.artifactFileRepository = artifactFileRepository;
        this.memoryNoteRepository = memoryNoteRepository;
    }

    @Override
    public List<SessionSummaryView> listSessions(String agentId) {
        List<SessionEntity> sessions = agentId == null || agentId.isBlank()
                ? sessionRepository.findTop20ByOrderByUpdatedAtDesc()
                : sessionRepository.findTop20ByAgentIdOrderByUpdatedAtDesc(agentId);
        return sessions.stream()
                .map(this::toSessionSummary)
                .toList();
    }

    @Override
    public SessionDetailView getSession(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        List<SessionMessageView> messages = sessionMessageRepository.findBySessionIdOrderBySeqNoAsc(sessionId).stream()
                .map(this::toSessionMessage)
                .toList();
        String title = normalizeTitle(session.getTitle());
        if (title == null) {
            title = sessionMessageRepository.findFirstBySessionIdAndRoleOrderBySeqNoAsc(session.getId(), "user")
                    .map(SessionMessageEntity::getContent)
                    .map(this::toSessionTitle)
                    .orElse(session.getId());
        }
        return new SessionDetailView(
                session.getId(),
                title,
                session.getAgentId(),
                session.getUserId(),
                session.getChannel(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages
        );
    }

    @Override
    public RunDetailView getRun(String runId) {
        RunRecordEntity run = runRecordRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        List<ToolAuditLogView> toolAudits = toolAuditLogRepository.findByRunIdOrderByIdAsc(runId).stream()
                .map(this::toToolAudit)
                .toList();
        List<ArtifactView> artifacts = artifactFileRepository.findByRunIdOrderByIdAsc(runId).stream()
                .map(this::toArtifact)
                .toList();
        return new RunDetailView(
                run.getId(),
                run.getSessionId(),
                run.getAgentId(),
                run.getUserId(),
                run.getLlmConfigId(),
                run.getLlmProvider(),
                run.getLlmModel(),
                run.getStatus(),
                run.getDetail(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                toolAudits,
                artifacts
        );
    }

    @Override
    public List<MemoryNoteView> listMemoryNotes(String agentId) {
        return memoryNoteRepository.findTop20ByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::toMemoryNote)
                .toList();
    }

    @Override
    public List<SessionMessageView> searchMessages(String query, String sessionId) {
        return sessionMessageRepository.searchByContent(query, blankToNull(sessionId)).stream()
                .limit(20)
                .map(this::toSessionMessage)
                .toList();
    }

    @Override
    public List<MemoryNoteView> searchMemoryNotes(String agentId, String query) {
        return memoryNoteRepository.findTop20ByAgentIdAndContentContainingIgnoreCaseOrderByCreatedAtDesc(agentId, query).stream()
                .map(this::toMemoryNote)
                .toList();
    }

    @Override
    public List<ArtifactView> searchArtifacts(String runId, String query) {
        return artifactFileRepository.findTop20ByRunIdAndNameContainingIgnoreCaseOrderByIdDesc(runId, query).stream()
                .map(this::toArtifact)
                .toList();
    }

    private SessionMessageView toSessionMessage(SessionMessageEntity entity) {
        return new SessionMessageView(
                entity.getId(),
                entity.getRunId(),
                entity.getRole(),
                entity.getMessageType(),
                entity.getContent(),
                entity.getToolName(),
                entity.getToolArgsJson(),
                entity.getToolResultJson(),
                entity.getSeqNo(),
                entity.getCreatedAt()
        );
    }

    private SessionSummaryView toSessionSummary(SessionEntity entity) {
        String title = normalizeTitle(entity.getTitle());
        if (title == null) {
            title = sessionMessageRepository.findFirstBySessionIdAndRoleOrderBySeqNoAsc(entity.getId(), "user")
                .map(SessionMessageEntity::getContent)
                .map(this::toSessionTitle)
                .orElse(entity.getId());
        }
        return new SessionSummaryView(
                entity.getId(),
                title,
                entity.getAgentId(),
                entity.getUserId(),
                entity.getChannel(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ToolAuditLogView toToolAudit(ToolAuditLogEntity entity) {
        return new ToolAuditLogView(
                entity.getId(),
                entity.getRequestId(),
                entity.getToolName(),
                entity.getPhase(),
                entity.isAllowed(),
                entity.isApprovalRequired(),
                entity.getSuccess(),
                entity.getExecuted(),
                entity.getReason(),
                entity.getArgumentsJson(),
                entity.getResultSummary(),
                entity.getErrorMessage(),
                entity.getDurationMillis(),
                entity.getCreatedAt()
        );
    }

    private ArtifactView toArtifact(ArtifactFileEntity entity) {
        return new ArtifactView(
                entity.getId(),
                entity.getSessionId(),
                entity.getRunId(),
                entity.getAgentId(),
                entity.getArtifactType(),
                entity.getName(),
                entity.getPath(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getCreatedAt()
        );
    }

    private MemoryNoteView toMemoryNote(MemoryNoteEntity entity) {
        return new MemoryNoteView(
                entity.getId(),
                entity.getAgentId(),
                entity.getSessionId(),
                entity.getRunId(),
                entity.getSource(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toSessionTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Untitled";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 36 ? normalized : normalized.substring(0, 36) + "...";
    }

    private String normalizeTitle(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
