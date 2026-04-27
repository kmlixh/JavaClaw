package com.janyee.agent.api;

public record MemoryNoteAdminRequest(
        Long id,
        String agentId,
        String sessionId,
        String scope,
        String source,
        String content
) {
}
