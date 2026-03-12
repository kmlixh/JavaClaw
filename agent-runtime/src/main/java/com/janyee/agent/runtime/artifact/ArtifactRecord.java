package com.janyee.agent.runtime.artifact;

import java.time.Instant;

public record ArtifactRecord(
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
