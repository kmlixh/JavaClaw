package com.janyee.agent.runtime.session;

public interface SessionTranscriptService {
    void appendUserMessage(String sessionId, String runId, String content);

    void appendAssistantMessage(String sessionId, String runId, String content);
}
