package com.janyee.agent.web.controller;

import com.janyee.agent.api.ApprovalActionResponse;
import com.janyee.agent.api.ApprovalRequestResponse;
import com.janyee.agent.runtime.approval.ApprovalResumeService;
import com.janyee.agent.security.ApprovalQueryService;
import com.janyee.agent.security.ApprovalRequestView;
import com.janyee.agent.security.ApprovalDecision;
import com.janyee.agent.security.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService approvalService;
    private final ApprovalQueryService approvalQueryService;
    private final ApprovalResumeService approvalResumeService;

    public ApprovalController(
            ApprovalService approvalService,
            ApprovalQueryService approvalQueryService,
            ApprovalResumeService approvalResumeService
    ) {
        this.approvalService = approvalService;
        this.approvalQueryService = approvalQueryService;
        this.approvalResumeService = approvalResumeService;
    }

    @GetMapping
    public List<ApprovalRequestResponse> list(
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "status", required = false) String status
    ) {
        log.debug("approval.list agentId={}, sessionId={}, status={}", agentId, sessionId, status);
        return approvalQueryService.listRequests(agentId, sessionId, status).stream()
                .map(view -> new ApprovalRequestResponse(
                        view.approvalRequestId(),
                        view.runId(),
                        view.sessionId(),
                        view.agentId(),
                        view.toolName(),
                        view.argumentsJson(),
                        view.reason(),
                        view.status(),
                        view.createdAt(),
                        view.updatedAt()
                ))
                .toList();
    }

    @GetMapping("/{id}")
    public ApprovalRequestResponse get(@PathVariable("id") String id) {
        log.debug("approval.get id={}", id);
        ApprovalRequestView view = approvalQueryService.getRequest(id);
        return new ApprovalRequestResponse(
                view.approvalRequestId(),
                view.runId(),
                view.sessionId(),
                view.agentId(),
                view.toolName(),
                view.argumentsJson(),
                view.reason(),
                view.status(),
                view.createdAt(),
                view.updatedAt()
        );
    }

    @PostMapping("/{id}/approve")
    public ApprovalActionResponse approve(@PathVariable("id") String id) {
        log.info("approval.approve.request id={}", id);
        ApprovalDecision decision = approvalService.approve(id);
        approvalResumeService.resumeApprovedRequest(id);
        log.info("approval.approve.accepted id={}, decision={}", id, decision);
        return new ApprovalActionResponse(id, decision.name(), "updated");
    }

    @PostMapping("/{id}/reject")
    public ApprovalActionResponse reject(@PathVariable("id") String id) {
        log.info("approval.reject.request id={}", id);
        ApprovalDecision decision = approvalService.reject(id);
        log.info("approval.reject.accepted id={}, decision={}", id, decision);
        return new ApprovalActionResponse(id, decision.name(), "updated");
    }
}
