package com.janyee.agent.runtime.admin;

import java.time.Instant;

public record KnowledgeEntryView(
        String id,
        String agentId,
        String title,
        String content,
        String contentType,
        String source,
        String tagsJson,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
