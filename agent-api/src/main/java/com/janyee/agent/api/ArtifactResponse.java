package com.janyee.agent.api;

import java.time.Instant;

public record ArtifactResponse(
        Long id,
        String sessionId,
        String runId,
        String agentId,
        String artifactType,
        String name,
        String path,
        String contentType,
        Long sizeBytes,
        Instant createdAt
) {
}
