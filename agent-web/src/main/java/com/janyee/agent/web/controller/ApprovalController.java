package com.janyee.agent.web.controller;

import com.janyee.agent.api.ApprovalActionResponse;
import com.janyee.agent.api.ApprovalRequestResponse;
import com.janyee.agent.runtime.approval.ApprovalResumeService;
import com.janyee.agent.security.ApprovalQueryService;
import com.janyee.agent.security.ApprovalRequestView;
import com.janyee.agent.security.ApprovalDecision;
import com.janyee.agent.security.ApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

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

    @GetMapping("/{id}")
    public ApprovalRequestResponse get(@PathVariable("id") String id) {
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
        ApprovalDecision decision = approvalService.approve(id);
        approvalResumeService.resumeApprovedRequest(id);
        return new ApprovalActionResponse(id, decision.name(), "updated");
    }

    @PostMapping("/{id}/reject")
    public ApprovalActionResponse reject(@PathVariable("id") String id) {
        ApprovalDecision decision = approvalService.reject(id);
        return new ApprovalActionResponse(id, decision.name(), "updated");
    }
}
