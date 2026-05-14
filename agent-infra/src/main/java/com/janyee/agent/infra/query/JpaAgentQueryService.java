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
    public com.janyee.agent.runtime.query.SessionPage listSessionsPaged(String agentId, int page, int size, String keyword) {
        return listSessionsPagedAdvanced(
                com.janyee.agent.runtime.query.SessionListFilter.of(agentId, page, size, keyword));
    }

    @Override
    public com.janyee.agent.runtime.query.SessionPage listSessionsPagedAdvanced(
            com.janyee.agent.runtime.query.SessionListFilter filter) {
        com.janyee.agent.infra.auth.SessionVisibility v =
                visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        int safePage = Math.max(0, filter.page());
        int safeSize = Math.min(Math.max(1, filter.size()), 200);
        String kw = filter.keyword() == null ? null : filter.keyword().trim().toLowerCase();
        boolean hasKw = kw != null && !kw.isEmpty();
        String runStatus = filter.runStatus() == null ? null : filter.runStatus().trim().toLowerCase();
        boolean filterRunning = "running".equals(runStatus);
        boolean filterIdle = "idle".equals(runStatus);

        org.springframework.data.jpa.domain.Specification<SessionEntity> spec = (root, q, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> conds = new java.util.ArrayList<>();
            if (filter.agentId() != null && !filter.agentId().isBlank()) {
                conds.add(cb.equal(root.get("agentId"), filter.agentId()));
            }
            if (filter.userId() != null && !filter.userId().isBlank()) {
                conds.add(cb.equal(root.get("userId"), filter.userId()));
            }
            if (filter.appId() != null && !filter.appId().isBlank()) {
                conds.add(cb.equal(root.get("appId"), filter.appId()));
            }
            if (filter.tenantId() != null && !filter.tenantId().isBlank()) {
                // 非 readAll 用户在 visibility 那一段已经被锁到 own tenant 上;这里加 tenant 过滤
                // 只有 readAll 时才有意义(让 sysadmin 跨租户审计)。要求过滤的值就是过滤的值,
                // visibility 的预测让 readTenant 跨租户传值时下面那道再加一刀也会匹配为空。
                conds.add(cb.equal(root.get("tenantId"), filter.tenantId()));
            }
            if (hasKw) {
                String like = "%" + kw + "%";
                conds.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), like),
                        cb.like(cb.lower(root.<String>get("id")), like),
                        cb.like(cb.lower(root.<String>get("agentId")), like)
                ));
            }
            // 权限过滤:readAll 不加;readTenant 限本租户;readOwn 限本用户
            if (!v.readAll()) {
                if (v.readTenant()) {
                    conds.add(cb.equal(root.get("tenantId"), v.tenantId() == null ? "" : v.tenantId()));
                } else {
                    conds.add(cb.equal(root.get("userId"), v.userId() == null ? "" : v.userId()));
                }
            }
            // runStatus = running / idle 用 EXISTS 子查询 join run_record
            if (filterRunning || filterIdle) {
                jakarta.persistence.criteria.Subquery<Long> sub = q.subquery(Long.class);
                jakarta.persistence.criteria.Root<com.janyee.agent.infra.persistence.entity.RunRecordEntity>
                        runRoot = sub.from(com.janyee.agent.infra.persistence.entity.RunRecordEntity.class);
                sub.select(cb.literal(1L)).where(
                        cb.equal(runRoot.get("sessionId"), root.get("id")),
                        runRoot.get("status").in(IN_PROGRESS_STATUSES_FOR_FILTER)
                );
                conds.add(filterRunning ? cb.exists(sub) : cb.not(cb.exists(sub)));
            }
            return cb.and(conds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(safePage, safeSize,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));
        org.springframework.data.domain.Page<SessionEntity> result = sessionRepository.findAll(spec, pageable);
        List<SessionSummaryView> rows = result.getContent().stream()
                .map(this::toSessionSummary)
                .toList();
        return new com.janyee.agent.runtime.query.SessionPage(
                rows, result.getTotalElements(), safePage, safeSize
        );
    }

    private static final List<String> IN_PROGRESS_STATUSES_FOR_FILTER = List.of(
            "RECEIVED", "CONTEXT_BUILT", "MODEL_RUNNING",
            "TOOL_REQUESTED", "TOOL_EXECUTING", "TOOL_RESULT_APPENDED", "WAITING_APPROVAL"
    );

    @Override
    public List<SessionSummaryView> listSessions(String agentId, int limit) {
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                com.janyee.agent.infra.auth.SecurityContextHolder.current();
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(principal);
        // 取上限到 500:再多没意义(前端可视范围 + 网络体积),并防止恶意大查询打 DB。
        int safeLimit = Math.min(Math.max(1, limit), 500);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, safeLimit);
        List<SessionEntity> sessions = agentId == null || agentId.isBlank()
                ? sessionRepository.findAllByOrderByUpdatedAtDesc(pageable)
                : sessionRepository.findByAgentIdOrderByUpdatedAtDesc(agentId, pageable);
        return sessions.stream()
                .filter(s -> v.canRead(s.getTenantId(), s.getUserId()))
                .map(this::toSessionSummary)
                .toList();
    }

    @Override
    public SessionDetailView getSession(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new com.janyee.agent.infra.auth.AuthService.AuthException(
                        "NOT_FOUND", "session not found: " + sessionId));
        // 越权防御:按 listSessions 同套规则判定 —— readAll(sysadmin) / readTenant(租户admin) /
        // readOwn(普通用户)。不通过抛 SecurityException → AuthExceptionAdvice 转 403。
        // 之前缺这条,任何登录用户拿到 sessionId 就能读 session 全文(含所有消息)。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        if (!v.canRead(session.getTenantId(), session.getUserId())) {
            throw new SecurityException("no permission to read session " + sessionId);
        }
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
                .orElseThrow(() -> new com.janyee.agent.infra.auth.AuthService.AuthException(
                        "NOT_FOUND", "run not found: " + runId));
        // 越权防御:run 是 session 派生的,按 run.tenantId/userId 校验。
        // RunDetailView 里有 planJson + tool 调用 + 产物,泄露代价更高。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        if (!v.canRead(run.getTenantId(), run.getUserId())) {
            throw new SecurityException("no permission to read run " + runId);
        }
        // 读路径不再 reconcile。读 DB 不能有副作用,更不能"顺手把别人在跑的 run 杀掉"——这是
        // 之前观察到的 bug 的根源。run 终态由 executeRun 自己写,JVM 重启由 RunStartupRecoveryService
        // 写,真卡死 60min 由 ScheduledRunReconcileSweeper 写,三条路径覆盖足够。
        return toRunDetail(run);
    }

    @Override
    public List<RunSummaryView> listRunsBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        // session 不可读 → 直接返空,不暴露 run id 列表。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !v.canRead(session.getTenantId(), session.getUserId())) {
            return List.of();
        }
        List<RunRecordEntity> runs = runRecordRepository.findBySessionId(sessionId);
        return runs.stream()
                .map(run -> new RunSummaryView(
                        run.getId(),
                        run.getSessionId(),
                        run.getStatus(),
                        run.getDetail(),
                        run.getCreatedAt(),
                        run.getUpdatedAt()
                ))
                .toList();
    }

    @Override
    public Optional<RunDetailView> findActiveRun(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        // session 不可读 → 当作没有 active run,避免外人用 sessionId 探测他人是否在跑任务。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !v.canRead(session.getTenantId(), session.getUserId())) {
            return Optional.empty();
        }
        return runRecordRepository.findFirstBySessionIdAndStatusInOrderByUpdatedAtDesc(
                        sessionId,
                        IN_PROGRESS_STATUSES_FOR_FILTER
                )
                .filter(run -> !"service restarted before run completed".equals(run.getDetail()))
                .map(this::toRunDetail);
    }

    @Override
    public List<RunDetailView> listVisibleActiveRuns() {
        com.janyee.agent.infra.auth.SessionVisibility v =
                visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        // 拉所有非终态 run。SessionVisibility 在 Java 层过滤 —— run_record 自己带 tenant/user,
        // 用 canRead 判一刀即可,不用再 join session。规模上 in-progress run 同时几十条到上百条,
        // 不会爆,不需要 DB 端再 push down 过滤。
        List<RunRecordEntity> inProgressRuns = runRecordRepository.findByStatusIn(IN_PROGRESS_STATUSES_FOR_FILTER);
        return inProgressRuns.stream()
                .filter(run -> !"service restarted before run completed".equals(run.getDetail()))
                .filter(run -> v.canRead(run.getTenantId(), run.getUserId()))
                .sorted((a, b) -> {
                    java.time.Instant aT = a.getUpdatedAt();
                    java.time.Instant bT = b.getUpdatedAt();
                    if (aT == null && bT == null) return 0;
                    if (aT == null) return 1;
                    if (bT == null) return -1;
                    return bT.compareTo(aT);  // updated_at desc
                })
                .map(this::toRunDetail)
                .toList();
    }

    private com.janyee.agent.infra.auth.SessionVisibility visibilityFor(com.janyee.agent.infra.auth.AuthPrincipal principal) {
        return com.janyee.agent.infra.auth.SessionVisibility.forPrincipal(principal);
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
        // memory note 落 user_id/tenant_id 跟 session 一样,用同一套可见性。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        return memoryNoteRepository.findTop20ByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(e -> v.canRead(e.getScopeTenantId(), e.getScopeUserId()))
                .map(this::toMemoryNote)
                .toList();
    }

    @Override
    public List<SessionMessageView> searchMessages(String query, String sessionId) {
        // 全表搜索若不过滤会跨租户跨用户搜到他人对话内容。先按 sessionId 拉出 session,
        // 再按 visibility 决定是否返回。无 sessionId 限定时,按 session 一一过滤。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        java.util.List<SessionMessageEntity> raw = sessionMessageRepository.searchByContent(query, blankToNull(sessionId));
        java.util.Map<String, Boolean> sessionAccessCache = new java.util.HashMap<>();
        return raw.stream()
                .filter(m -> sessionAccessCache.computeIfAbsent(m.getSessionId(), sid -> {
                    SessionEntity s = sessionRepository.findById(sid).orElse(null);
                    return s != null && v.canRead(s.getTenantId(), s.getUserId());
                }))
                .limit(20)
                .map(this::toSessionMessage)
                .toList();
    }

    @Override
    public List<MemoryNoteView> searchMemoryNotes(String agentId, String query) {
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        return memoryNoteRepository.findTop20ByAgentIdAndContentContainingIgnoreCaseOrderByCreatedAtDesc(agentId, query).stream()
                .filter(e -> v.canRead(e.getScopeTenantId(), e.getScopeUserId()))
                .map(this::toMemoryNote)
                .toList();
    }

    @Override
    public List<ArtifactView> searchArtifacts(String runId, String query) {
        // 看 runId 对应 run 是否对当前用户可见。不可见 → 返空。
        com.janyee.agent.infra.auth.SessionVisibility v = visibilityFor(com.janyee.agent.infra.auth.SecurityContextHolder.current());
        RunRecordEntity run = runRecordRepository.findById(runId).orElse(null);
        if (run == null || !v.canRead(run.getTenantId(), run.getUserId())) {
            return List.of();
        }
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
                entity.getCreatedAt(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getTotalTokens()
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
