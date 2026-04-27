package com.janyee.agent.runtime.bridge;

public record ExternalBridgeResult(
        boolean success,
        String summary,
        String resultJson,
        String error
) {
}

