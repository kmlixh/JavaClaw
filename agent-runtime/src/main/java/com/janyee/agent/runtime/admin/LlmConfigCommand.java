package com.janyee.agent.runtime.admin;

public record LlmConfigCommand(
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
        // 通用 scope 4 字段。null 表示沿用现有(更新场景)或由 service 推断默认(新建场景)
        String scopeType,
        String scopeTenantId,
        String appId,
        String scopeUserId
) {
}
