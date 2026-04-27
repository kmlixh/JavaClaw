package com.janyee.agent.runtime.session;

public interface SessionTranscriptService {
    /**
     * Back-compat overload — stores a user message with no @mention references and no
     * attachments. Callers that actually have references/attachments (chat send path) should
     * use {@link #appendUserMessage(String, String, String, String, String)} so the payload
     * survives page refresh and historical-session loads.
     */
    default void appendUserMessage(String sessionId, String runId, String content) {
        appendUserMessage(sessionId, runId, content, null, null);
    }

    /**
     * @param referencesJson  JSON array of ChatContextReference payloads, or null when empty.
     * @param attachmentsJson JSON array of ChatAttachment payloads (spatial regions / images /
     *                        files), or null when empty.
     */
    void appendUserMessage(String sessionId, String runId, String content, String referencesJson, String attachmentsJson);

    void appendAssistantMessage(String sessionId, String runId, String content);

    void appendToolMessage(String sessionId, String runId, String toolName, String toolArgsJson, String toolResultJson, String content);
}
