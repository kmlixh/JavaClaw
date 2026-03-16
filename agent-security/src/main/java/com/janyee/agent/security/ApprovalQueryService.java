package com.janyee.agent.security;

import java.util.List;

public interface ApprovalQueryService {
    ApprovalRequestView getRequest(String requestId);

    List<ApprovalRequestView> listRequests(String agentId, String sessionId, String status);
}
