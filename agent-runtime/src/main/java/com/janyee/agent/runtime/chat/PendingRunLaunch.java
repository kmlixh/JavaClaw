package com.janyee.agent.runtime.chat;

import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;

import java.util.List;

public record PendingRunLaunch(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String llmConfigId,
        String llmModel,
        String message,
        List<ChatContextReference> references,
        List<ChatAttachment> attachments
) {
}
