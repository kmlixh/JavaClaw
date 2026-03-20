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

    ArtifactRecord saveBinaryArtifact(
            String agentId,
            String sessionId,
            String runId,
            String artifactType,
            String name,
            String contentType,
            byte[] content
    );

    ArtifactBinary loadArtifact(Long artifactId);
}
