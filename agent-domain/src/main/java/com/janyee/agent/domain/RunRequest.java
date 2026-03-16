package com.janyee.agent.domain;

public record RunRequest(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String message,
        boolean resume,
        String llmConfigId,
        java.util.List<ChatContextReference> references,
        java.util.List<ChatAttachment> attachments
) {
}
