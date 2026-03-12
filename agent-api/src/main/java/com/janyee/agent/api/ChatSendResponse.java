package com.janyee.agent.api;

public record ChatSendResponse(
        String sessionId,
        String runId,
        String status
) {
}
