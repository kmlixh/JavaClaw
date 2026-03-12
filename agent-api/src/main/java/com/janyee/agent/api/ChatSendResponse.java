package com.janyee.agent.api;

public record ChatSendResponse(
        String sessionId,
        String agentId,
        String runId,
        String status
) {
}
