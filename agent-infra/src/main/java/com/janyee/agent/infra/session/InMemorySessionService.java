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
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        // 越权防御:跟读路径同档判定 —— readAll(sysadmin)/readTenant(本租户)/readOwn(本人)。
        // 之前缺这条,任何登录用户拿到 sessionId 就能 DELETE。
        com.janyee.agent.infra.auth.SessionVisibility v =
                com.janyee.agent.infra.auth.SessionVisibility.forCurrent();
        if (!v.canRead(session.getTenantId(), session.getUserId())) {
            throw new SecurityException("no permission to delete session " + sessionId);
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
        // P3:从当前认证上下文读 tenant/app/user 写进新 session。
        // ⚠ user_id 必须以 principal 为准:之前直接信任前端传的 userId 字段,
        // embed 模式下 SDK init 会塞 form.userId 进来,跟 OAuth token 解析的真实
        // principal.userId 对不上 —— 导致 listSessions 用 principal.userId 过滤
        // 时永远 match 不到,xmap embed 的"最近会话"列表始终为空。
        // 只有匿名(没 token)时才退回到前端 userId,保留旧行为兜底。
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                com.janyee.agent.infra.auth.SecurityContextHolder.current();
        String resolvedUserId = principal != null && !principal.anonymous()
                && principal.userId() != null && !principal.userId().isBlank()
                ? principal.userId()
                : (userId == null || userId.isBlank() ? "anonymous" : userId);
        session.setUserId(resolvedUserId);
        session.setChannel("web");
        session.setStatus("ACTIVE");
        if (principal != null) {
            session.setTenantId(principal.tenantId());
            session.setAppId(principal.appId());
        }
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
