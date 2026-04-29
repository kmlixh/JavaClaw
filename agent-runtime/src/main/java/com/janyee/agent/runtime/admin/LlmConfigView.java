package com.janyee.agent.runtime.admin;

import java.time.Instant;

public record LlmConfigView(
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
        // 通用 scope 4 字段:scopeType ∈ SYSTEM|PUBLIC|TENANT|APP|USER
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
