package com.janyee.agent.api;

import java.time.Instant;

public record KnowledgeEntryResponse(
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
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
