package com.janyee.agent.security;

public interface ApprovalService {
    String createRequest(
            String runId,
            String sessionId,
            String agentId,
            String toolName,
            String argumentsJson,
            String reason
    );

    ApprovalDecision decide(String requestId);

    ApprovalDecision approve(String requestId);

    ApprovalDecision reject(String requestId);
}
