package com.janyee.agent.runtime.artifact;

public interface ArtifactService {
    ArtifactRecord saveTextArtifact(
            String agentId,
            String sessionId,
            String runId,
            String artifactType,
            String name,
            String contentType,
            String content
    );
}
