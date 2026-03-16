package com.janyee.agent.api;

public record KnowledgeEntryRequest(
        String id,
        String agentId,
        String title,
        String content,
        String contentType,
        String source,
        String tagsJson,
        boolean enabled
) {
}
