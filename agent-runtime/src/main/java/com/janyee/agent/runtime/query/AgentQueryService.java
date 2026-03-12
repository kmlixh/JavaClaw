package com.janyee.agent.runtime.query;

public interface AgentQueryService {
    SessionDetailView getSession(String sessionId);

    RunDetailView getRun(String runId);
}
