package com.janyee.agent.domain;

import java.time.Instant;

public record AgentEvent(
        AgentEventType type,
        String sessionId,
        String runId,
        String content,
        Instant timestamp
) {
    public static AgentEvent now(AgentEventType type, String sessionId, String runId, String content) {
        return new AgentEvent(type, sessionId, runId, content, Instant.now());
    }
}
