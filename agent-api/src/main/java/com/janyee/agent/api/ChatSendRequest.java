package com.janyee.agent.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatSendRequest(
        String sessionId,
        String agentId,
        String userId,
        String llmConfigId,
        String llmModel,
        @NotBlank String message,
        List<ChatContextReference> references,
        List<ChatAttachment> attachments
) {
}
