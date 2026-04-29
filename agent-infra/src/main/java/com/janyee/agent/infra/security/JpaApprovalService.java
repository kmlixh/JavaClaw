package com.janyee.agent.infra.security;

import com.janyee.agent.infra.auth.SessionVisibility;
import com.janyee.agent.infra.persistence.entity.ApprovalRequestEntity;
import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.security.ApprovalDecision;
import com.janyee.agent.security.ApprovalQueryService;
import com.janyee.agent.security.ApprovalRequestView;
import com.janyee.agent.security.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JpaApprovalService implements ApprovalService, ApprovalQueryService {

    private static final Logger log = LoggerFactory.getLogger(JpaApprovalService.class);

    private final ApprovalRequestRepository repository;
    private final SessionRepository sessionRepository;

    public JpaApprovalService(
            ApprovalRequestRepository repository,
            SessionRepository sessionRepository
    ) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 用 approval 关联的 session 解析 tenantId/userId,然后交给 SessionVisibility 判定。
     * 拿不到 session(已删 / 数据脏)→ 当作不可见,保守拒绝。
     */
    private boolean canAccess(ApprovalRequestEntity entity, SessionVisibility v) {
        if (entity == null) return false;
        SessionEntity session = entity.getSessionId() == null
                ? null
                : sessionRepository.findById(entity.getSessionId()).orElse(null);
        if (session == null) {
            // approval 不该有 dangling sessionId,这里保守拒绝
            return v.readAll();
        }
        return v.canRead(session.getTenantId(), session.getUserId());
    }

    private void requireAccess(ApprovalRequestEntity entity, String operation) {
        SessionVisibility v = SessionVisibility.forCurrent();
        if (!canAccess(entity, v)) {
            log.warn("approval.{}.forbidden id={}, sessionId={}", operation,
                    entity.getId(), entity.getSessionId());
            throw new SecurityException("no permission to " + operation
                    + " approval request " + entity.getId());
        }
    }

    @Override
    @Transactional
    public String createRequest(
            String runId,
            String sessionId,
            String agentId,
            String toolName,
            String argumentsJson,
            String reason
    ) {
        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setRunId(runId);
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        entity.setToolName(toolName);
        entity.setArgumentsJson(argumentsJson);
        entity.setReason(reason);
        entity.setStatus("PENDING");
        repository.save(entity);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalDecision decide(String requestId) {
        ApprovalRequestEntity entity = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + requestId));
        return switch (entity.getStatus()) {
            case "APPROVED" -> ApprovalDecision.APPROVED;
            case "REJECTED" -> ApprovalDecision.REJECTED;
            default -> throw new IllegalStateException("approval request still pending: " + requestId);
        };
    }

    @Override
    @Transactional
    public ApprovalDecision approve(String requestId) {
        return updateStatus(requestId, "APPROVED", ApprovalDecision.APPROVED);
    }

    @Override
    @Transactional
    public ApprovalDecision reject(String requestId) {
        return updateStatus(requestId, "REJECTED", ApprovalDecision.REJECTED);
    }

    private ApprovalDecision updateStatus(String requestId, String status, ApprovalDecision decision) {
        ApprovalRequestEntity entity = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + requestId));
        // 写操作的越权防御:批准/拒绝必须是该 session 的可见者(系统管理员、租户管理员之于本租户、
        // 或 session.user_id == 自己)。否则任何登录用户拿到 approval id 就能改决策。
        requireAccess(entity, "decide-on");
        if (!"PENDING".equals(entity.getStatus())) {
            throw new IllegalStateException("approval request already decided: " + requestId + ", currentStatus=" + entity.getStatus());
        }
        entity.setStatus(status);
        repository.save(entity);
        return decision;
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalRequestView getRequest(String requestId) {
        ApprovalRequestEntity entity = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + requestId));
        // 读单条同样要按 session 关联的 tenant/user 判定可见性。
        requireAccess(entity, "read");
        return toView(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalRequestView> listRequests(String agentId, String sessionId, String status) {
        List<ApprovalRequestEntity> entities;
        if (sessionId != null && !sessionId.isBlank()) {
            entities = repository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        } else if (agentId != null && !agentId.isBlank()) {
            entities = repository.findByAgentIdOrderByCreatedAtDesc(agentId);
        } else if (status != null && !status.isBlank()) {
            entities = repository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            entities = repository.findAllByOrderByCreatedAtDesc();
        }
        // 按 session 缓存式过滤:同一批 approval 多条共享同一个 sessionId 时,session 只查一次。
        // 之前 list 完全没过滤,任何人都能看跨租户全部 approval。
        SessionVisibility v = SessionVisibility.forCurrent();
        Map<String, Boolean> sessionAccessCache = new HashMap<>();
        return entities.stream()
                .filter(entity -> agentId == null || agentId.isBlank() || agentId.equals(entity.getAgentId()))
                .filter(entity -> sessionId == null || sessionId.isBlank() || sessionId.equals(entity.getSessionId()))
                .filter(entity -> status == null || status.isBlank() || status.equals(entity.getStatus()))
                .filter(entity -> sessionAccessCache.computeIfAbsent(
                        entity.getSessionId() == null ? "" : entity.getSessionId(),
                        sid -> {
                            if (sid == null || sid.isEmpty()) return v.readAll();
                            SessionEntity session = sessionRepository.findById(sid).orElse(null);
                            if (session == null) return v.readAll();
                            return v.canRead(session.getTenantId(), session.getUserId());
                        }))
                .sorted(Comparator.comparing(ApprovalRequestEntity::getCreatedAt).reversed())
                .map(this::toView)
                .toList();
    }

    private ApprovalRequestView toView(ApprovalRequestEntity entity) {
        return new ApprovalRequestView(
                entity.getId(),
                entity.getRunId(),
                entity.getSessionId(),
                entity.getAgentId(),
                entity.getToolName(),
                entity.getArgumentsJson(),
                entity.getReason(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
