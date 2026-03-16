package com.janyee.agent.infra.security;

import com.janyee.agent.infra.persistence.entity.ApprovalRequestEntity;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.security.ApprovalDecision;
import com.janyee.agent.security.ApprovalQueryService;
import com.janyee.agent.security.ApprovalRequestView;
import com.janyee.agent.security.ApprovalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class JpaApprovalService implements ApprovalService, ApprovalQueryService {

    private final ApprovalRequestRepository repository;

    public JpaApprovalService(ApprovalRequestRepository repository) {
        this.repository = repository;
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
        return entities.stream()
                .filter(entity -> agentId == null || agentId.isBlank() || agentId.equals(entity.getAgentId()))
                .filter(entity -> sessionId == null || sessionId.isBlank() || sessionId.equals(entity.getSessionId()))
                .filter(entity -> status == null || status.isBlank() || status.equals(entity.getStatus()))
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
