package com.janyee.agent.api;

import jakarta.validation.constraints.NotBlank;

public record ChatSendRequest(
        String sessionId,
        String agentId,
        String userId,
        String llmConfigId,
        @NotBlank String message
) {
}
