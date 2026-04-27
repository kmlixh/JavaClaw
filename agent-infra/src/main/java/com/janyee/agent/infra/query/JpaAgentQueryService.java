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
import com.janyee.agent.infra.run.StaleRunReconciler;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.ArtifactView;
import com.janyee.agent.runtime.query.SessionSummaryView;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.RunSummaryView;
import com.janyee.agent.runtime.query.MemoryNoteView;
import com.janyee.agent.runtime.query.SessionDetailView;
import com.janyee.agent.runtime.query.SessionMessageView;
import com.janyee.agent.runtime.query.ToolAuditLogView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class JpaAgentQueryService implements AgentQueryService {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final RunRecordRepository runRecordRepository;
    private final ToolAuditLogRepository toolAuditLogRepository;
    private final ArtifactFileRepository artifactFileRepository;
    private final MemoryNoteRepository memoryNoteRepository;
    private final StaleRunReconciler staleRunReconciler;

    public JpaAgentQueryService(
            SessionRepository sessionRepository,
            SessionMessageRepository sessionMessageRepository,
            RunRecordRepository runRecordRepository,
            ToolAuditLogRepository toolAuditLogRepository,
            ArtifactFileRepository artifactFileRepository,
            MemoryNoteRepository memoryNoteRepository,
            StaleRunReconciler staleRunReconciler
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.runRecordRepository = runRecordRepository;
        this.toolAuditLogRepository = toolAuditLogRepository;
        this.artifactFileRepository = artifactFileRepository;
        this.memoryNoteRepository = memoryNoteRepository;
        this.staleRunReconciler = staleRunReconciler;
    }

    @Override
    public List<SessionSummaryView> listSessions(String agentId) {
        // P3:按 permission 分层可见性
        //   session.read.all     → 不过滤(系统管理员跨租户看)
        //   session.read.tenant  → tenant_id == 当前租户(租户 admin)
        //   session.read.own     → user_id == 当前 user(普通用户,默认)
        // 匿名期 SUPER_ADMIN 默认拥有 read.all,老行为不变。
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                com.janyee.agent.infra.auth.SecurityContextHolder.current();
        java.util.Set<String> perms = principal.permissions();
        boolean readAll = principal.anonymous() || perms.contains("session.read.all");
        boolean readTenant = readAll || perms.contains("session.read.tenant");
        List<SessionEntity> sessions = agentId == null || agentId.isBlank()
                ? sessionRepository.findTop20ByOrderByUpdatedAtDesc()
                : sessionRepository.findTop20ByAgentIdOrderByUpdatedAtDesc(agentId);
        return sessions.stream()
                .filter(s -> readAll
                        || (readTenant && java.util.Objects.equals(s.getTenantId(), principal.tenantId()))
                        || (!readTenant && java.util.Objects.equals(s.getUserId(), principal.userId())))
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
        // 如果 DB 还挂着 in-progress 但服务端没有活跃执行 —— 写回 FAILED 并用修正后的 entity 生成 view。
        staleRunReconciler.reconcileIfStale(run);
        return toRunDetail(run);
    }

    @Override
    public List<RunSummaryView> listRunsBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<RunRecordEntity> runs = runRecordRepository.findBySessionId(sessionId);
        // 逐条经过 reconciler —— 任何 DB 还挂着 in-progress 但 registry 没登记的都会被改写成 FAILED。
        // 这样前端拿到的 status 和"server 是否真的在跑"完全同步，不再依赖 event 流推断。
        return runs.stream()
                .map(run -> {
                    staleRunReconciler.reconcileIfStale(run);
                    return new RunSummaryView(
                            run.getId(),
                            run.getSessionId(),
                            run.getStatus(),
                            run.getDetail(),
                            run.getCreatedAt(),
                            run.getUpdatedAt()
                    );
                })
                .toList();
    }

    @Override
    public Optional<RunDetailView> findActiveRun(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return runRecordRepository.findFirstBySessionIdAndStatusInOrderByUpdatedAtDesc(
                        sessionId,
                        List.of("RECEIVED", "CONTEXT_BUILT", "MODEL_RUNNING", "TOOL_REQUESTED", "TOOL_EXECUTING", "TOOL_RESULT_APPENDED", "WAITING_APPROVAL")
                )
                .filter(run -> !"service restarted before run completed".equals(run.getDetail()))
                .filter(run -> !staleRunReconciler.reconcileIfStale(run))
                .map(this::toRunDetail);
    }

    private RunDetailView toRunDetail(RunRecordEntity run) {
        String runId = run.getId();
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
                run.getRequestMessage(),
                run.getRequestReferencesJson(),
                run.getRequestAttachmentsJson(),
                run.getPlanJson(),
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
                entity.getReferencesJson(),
                entity.getAttachmentsJson(),
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
