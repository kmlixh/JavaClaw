package com.janyee.agent.infra.approval;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.approval.ApprovalResumeService;
import com.janyee.agent.domain.RunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultApprovalResumeService implements ApprovalResumeService {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovalResumeService.class);

    private final ApprovalRequestRepository approvalRequestRepository;
    private final RunRecordRepository runRecordRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final AgentRunner agentRunner;

    public DefaultApprovalResumeService(
            ApprovalRequestRepository approvalRequestRepository,
            RunRecordRepository runRecordRepository,
            SessionMessageRepository sessionMessageRepository,
            AgentRunner agentRunner
    ) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.runRecordRepository = runRecordRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.agentRunner = agentRunner;
    }

    @Override
    public void resumeApprovedRequest(String approvalRequestId) {
        log.info("approval.resume.start approvalRequestId={}", approvalRequestId);
        var approval = approvalRequestRepository.findById(approvalRequestId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + approvalRequestId));
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new IllegalStateException("approval request is not approved: " + approvalRequestId);
        }

        RunRecordEntity run = runRecordRepository.findById(approval.getRunId())
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + approval.getRunId()));
        SessionMessageEntity userMessage = sessionMessageRepository.findFirstByRunIdAndRoleOrderByIdAsc(run.getId(), "user")
                .orElseThrow(() -> new IllegalArgumentException("user message not found for run: " + run.getId()));

        log.info("approval.resume.dispatch approvalRequestId={}, runId={}, sessionId={}, agentId={}, llmConfigId={}",
                approvalRequestId, run.getId(), run.getSessionId(), run.getAgentId(), run.getLlmConfigId());
        agentRunner.run(new RunRequest(
                run.getId(),
                run.getSessionId(),
                run.getAgentId(),
                run.getUserId(),
                userMessage.getContent(),
                true,
                run.getLlmConfigId(),
                java.util.List.of(),
                java.util.List.of()
        )).doOnComplete(() -> log.info("approval.resume.complete approvalRequestId={}, runId={}", approvalRequestId, run.getId()))
                .doOnError(error -> log.error("approval.resume.error approvalRequestId={}, runId={}", approvalRequestId, run.getId(), error))
                .subscribe();
    }
}
