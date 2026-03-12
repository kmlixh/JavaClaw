package com.janyee.agent.infra.artifact;

import com.janyee.agent.infra.persistence.entity.ArtifactFileEntity;
import com.janyee.agent.infra.persistence.repository.ArtifactFileRepository;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Service
public class JpaArtifactService implements ArtifactService {

    private final ArtifactFileRepository artifactFileRepository;
    private final WorkspaceService workspaceService;

    public JpaArtifactService(ArtifactFileRepository artifactFileRepository, WorkspaceService workspaceService) {
        this.artifactFileRepository = artifactFileRepository;
        this.workspaceService = workspaceService;
    }

    @Override
    public ArtifactRecord saveTextArtifact(
            String agentId,
            String sessionId,
            String runId,
            String artifactType,
            String name,
            String contentType,
            String content
    ) {
        try {
            Path workspaceRoot = workspaceService.getWorkspaceRoot(agentId);
            Path artifactDir = workspaceRoot.resolve("artifacts").resolve(runId).normalize();
            if (!artifactDir.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("artifact path escape detected");
            }
            Files.createDirectories(artifactDir);

            String safeName = sanitizeName(name);
            String storedFileName = UUID.randomUUID() + "-" + safeName;
            Path target = artifactDir.resolve(storedFileName).normalize();
            Files.writeString(target, content, StandardCharsets.UTF_8);

            ArtifactFileEntity entity = new ArtifactFileEntity();
            entity.setSessionId(sessionId);
            entity.setRunId(runId);
            entity.setAgentId(agentId);
            entity.setArtifactType(artifactType);
            entity.setName(safeName);
            entity.setPath(workspaceRoot.relativize(target).toString().replace('\\', '/'));
            entity.setContentType(contentType);
            entity.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
            ArtifactFileEntity saved = artifactFileRepository.save(entity);

            return new ArtifactRecord(
                    saved.getId(),
                    saved.getSessionId(),
                    saved.getRunId(),
                    saved.getAgentId(),
                    saved.getArtifactType(),
                    saved.getName(),
                    saved.getPath(),
                    saved.getContentType(),
                    saved.getSizeBytes(),
                    saved.getCreatedAt() != null ? saved.getCreatedAt() : Instant.now()
            );
        } catch (Exception error) {
            throw new IllegalStateException("failed to save artifact", error);
        }
    }

    private String sanitizeName(String value) {
        String fallback = value == null || value.isBlank() ? "artifact.txt" : value;
        return fallback.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    }
}
