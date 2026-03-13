package com.janyee.agent.runtime.session;

public interface SessionService {
    SessionSnapshot ensureSession(String sessionId, String agentId, String userId);

    SessionSnapshot renameSession(String sessionId, String title);

    void deleteSession(String sessionId);
}
