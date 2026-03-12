package com.janyee.agent.runtime.agent;

public record AgentRouteRequest(
        String channel,
        String userId,
        String sessionId,
        String requestedAgentId
) {
}
