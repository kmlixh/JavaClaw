package com.janyee.agent.runtime.session;

public record SessionSnapshot(
        String sessionId,
        String agentId,
        String userId
) {
}
