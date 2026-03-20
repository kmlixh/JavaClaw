package com.janyee.agent.infra.artifact;

import com.janyee.agent.infra.persistence.entity.ArtifactFileEntity;
import com.janyee.agent.infra.persistence.repository.ArtifactFileRepository;
import com.janyee.agent.runtime.artifact.ArtifactBinary;
import com.janyee.agent.runtime.artifact.ArtifactRecord;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
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
        return saveBinaryArtifact(
                agentId,
                sessionId,
                runId,
                artifactType,
                name,
                contentType,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public ArtifactRecord saveBinaryArtifact(
            String agentId,
            String sessionId,
            String runId,
            String artifactType,
            String name,
            String contentType,
            byte[] content
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
            Files.write(target, content);

            ArtifactFileEntity entity = new ArtifactFileEntity();
            entity.setSessionId(sessionId);
            entity.setRunId(runId);
            entity.setAgentId(agentId);
            entity.setArtifactType(artifactType);
            entity.setName(safeName);
            entity.setPath(workspaceRoot.relativize(target).toString().replace('\\', '/'));
            entity.setContentType(contentType);
            entity.setSizeBytes((long) content.length);
            ArtifactFileEntity saved = artifactFileRepository.save(entity);

            return toRecord(saved);
        } catch (Exception error) {
            throw new IllegalStateException("failed to save artifact", error);
        }
    }

    @Override
    public ArtifactBinary loadArtifact(Long artifactId) {
        ArtifactFileEntity entity = artifactFileRepository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("artifact not found: " + artifactId));
        try {
            Path workspaceRoot = workspaceService.getWorkspaceRoot(entity.getAgentId());
            Path target = workspaceRoot.resolve(entity.getPath()).normalize();
            if (!target.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("artifact path escape detected");
            }
            return new ArtifactBinary(toRecord(entity), Files.readAllBytes(target));
        } catch (InvalidPathException error) {
            throw new IllegalStateException("invalid artifact path", error);
        } catch (Exception error) {
            throw new IllegalStateException("failed to load artifact", error);
        }
    }

    private String sanitizeName(String value) {
        String fallback = value == null || value.isBlank() ? "artifact.txt" : value;
        return fallback.replaceAll("[\\\\/:*?\"<>|]+", "-");
    }

    private ArtifactRecord toRecord(ArtifactFileEntity saved) {
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
    }
}
