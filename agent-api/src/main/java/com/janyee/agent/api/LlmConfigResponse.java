package com.janyee.agent.api;

public record LlmConfigResponse(
        String configId,
        String provider,
        String displayName,
        String model,
        boolean stream,
        boolean defaultConfig
) {
}
