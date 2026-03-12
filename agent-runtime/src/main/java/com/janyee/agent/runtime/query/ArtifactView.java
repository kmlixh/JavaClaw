package com.janyee.agent.runtime.query;

import java.time.Instant;

public record ArtifactView(
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
