package com.janyee.agent.runtime.artifact;

public record ArtifactBinary(
        ArtifactRecord artifact,
        byte[] content
) {
}
