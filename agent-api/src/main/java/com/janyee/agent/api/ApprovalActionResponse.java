package com.janyee.agent.api;

public record ApprovalActionResponse(
        String approvalRequestId,
        String decision,
        String status
) {
}
