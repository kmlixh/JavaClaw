package com.janyee.agent.runtime.model;

public record LlmConfigDescriptor(
        String configId,
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
