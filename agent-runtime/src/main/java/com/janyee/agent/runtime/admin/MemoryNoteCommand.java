package com.janyee.agent.runtime.admin;

/**
 * Admin-side command for editing memory_note rows. Nullable id means "create new". Scope
 * MUST be one of {@code session|agent|global}; content is required.
 */
public record MemoryNoteCommand(
        Long id,
        String agentId,
        String sessionId,
        String scope,
        String source,
        String content
) {
}
