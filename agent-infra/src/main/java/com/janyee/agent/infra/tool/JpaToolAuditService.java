package com.janyee.agent.infra.tool;

import com.janyee.agent.infra.persistence.entity.ToolAuditLogEntity;
import com.janyee.agent.infra.persistence.repository.ToolAuditLogRepository;
import com.janyee.agent.runtime.loop.ToolAuditService;
import com.janyee.agent.runtime.loop.ToolCallDecision;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolCallRequest;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaToolAuditService implements ToolAuditService {

    private final ToolAuditLogRepository repository;

    public JpaToolAuditService(ToolAuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void recordPolicyDecision(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision) {
        ToolAuditLogEntity entity = baseEntity(context, request);
        entity.setPhase("POLICY_DECISION");
        entity.setAllowed(decision.allowed());
        entity.setApprovalRequired(decision.approvalRequired());
        entity.setArgumentsJson(decision.normalizedArgumentsJson());
        entity.setReason(decision.reason());
        repository.save(entity);
    }

    @Override
    @Transactional
    public void recordExecutionOutcome(ToolLoopContext context, ToolCallOutcome outcome) {
        ToolAuditLogEntity entity = baseEntity(context, outcome.request());
        entity.setPhase("EXECUTION_OUTCOME");
        entity.setAllowed(outcome.decision().allowed());
        entity.setApprovalRequired(outcome.decision().approvalRequired());
        entity.setExecuted(outcome.executed());
        entity.setSuccess(outcome.success());
        entity.setArgumentsJson(outcome.decision().normalizedArgumentsJson());
        entity.setReason(outcome.decision().reason());
        entity.setResultSummary(outcome.toolResult() != null ? outcome.toolResult().summary() : null);
        entity.setErrorMessage(outcome.errorMessage());
        entity.setDurationMillis(outcome.durationMillis());
        repository.save(entity);
    }

    private ToolAuditLogEntity baseEntity(ToolLoopContext context, ToolCallRequest request) {
        ToolAuditLogEntity entity = new ToolAuditLogEntity();
        entity.setRunId(context.runId());
        entity.setSessionId(context.sessionId());
        entity.setAgentId(context.agentId());
        entity.setRequestId(request.requestId());
        entity.setToolName(request.toolName());
        return entity;
    }
}
