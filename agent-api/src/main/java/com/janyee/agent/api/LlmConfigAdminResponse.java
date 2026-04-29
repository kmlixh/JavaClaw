package com.janyee.agent.api;

import java.time.Instant;

public record LlmConfigAdminResponse(
        String id,
        String provider,
        String displayName,
        String model,
        String modelMappingJson,
        String baseUrl,
        String apiKey,
        String chatPath,
        boolean stream,
        boolean enabled,
        boolean defaultConfig,
        Instant createdAt,
        Instant updatedAt,
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
