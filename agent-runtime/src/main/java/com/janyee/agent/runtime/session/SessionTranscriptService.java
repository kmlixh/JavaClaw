package com.janyee.agent.runtime.session;

public interface SessionTranscriptService {
    void appendUserMessage(String sessionId, String runId, String content);

    void appendAssistantMessage(String sessionId, String runId, String content);

    void appendToolMessage(String sessionId, String runId, String toolName, String toolArgsJson, String toolResultJson, String content);
}
