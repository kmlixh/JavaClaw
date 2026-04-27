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
        boolean defaultConfig
) {
}
