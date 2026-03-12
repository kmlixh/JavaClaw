package com.janyee.agent.infra.query;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.entity.ToolAuditLogEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.infra.persistence.repository.ToolAuditLogRepository;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
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

    public JpaAgentQueryService(
            SessionRepository sessionRepository,
            SessionMessageRepository sessionMessageRepository,
            RunRecordRepository runRecordRepository,
            ToolAuditLogRepository toolAuditLogRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.runRecordRepository = runRecordRepository;
        this.toolAuditLogRepository = toolAuditLogRepository;
    }

    @Override
    public SessionDetailView getSession(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        List<SessionMessageView> messages = sessionMessageRepository.findBySessionIdOrderBySeqNoAsc(sessionId).stream()
                .map(this::toSessionMessage)
                .toList();
        return new SessionDetailView(
                session.getId(),
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
        return new RunDetailView(
                run.getId(),
                run.getSessionId(),
                run.getAgentId(),
                run.getUserId(),
                run.getStatus(),
                run.getDetail(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                toolAudits
        );
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
}
