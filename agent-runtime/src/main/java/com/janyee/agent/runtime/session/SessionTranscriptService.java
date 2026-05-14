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

    /**
     * Back-compat overload — 不带 token 用量。内部错误兜底类(如 "[运行失败] ..." )沿用,
     * 因为这种 path 没经过完整 LLM 调用,统计 token 没意义。
     */
    default void appendAssistantMessage(String sessionId, String runId, String content) {
        appendAssistantMessage(sessionId, runId, content, null, null, null);
    }

    /**
     * 带 token 用量的版本 —— 由 SimpleAgentRunner 在 LLM 调用链结束(loopResult 含累计 token)
     * 时调用。三个值在 LLM 完整跑完一个 run 时填,部分失败/取消可能为 null。
     */
    void appendAssistantMessage(String sessionId, String runId, String content,
                                Integer promptTokens, Integer completionTokens, Integer totalTokens);

    void appendToolMessage(String sessionId, String runId, String toolName, String toolArgsJson, String toolResultJson, String content);
}
