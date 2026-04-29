package com.janyee.agent.runtime.admin;

public record KnowledgeEntryCommand(
        String id,
        String agentId,
        String title,
        String content,
        String contentType,
        String source,
        String tagsJson,
        boolean enabled,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
