package com.janyee.agent.infra.tool;

import com.janyee.agent.workspace.WorkspaceService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class WorkspacePathSupport {

    private WorkspacePathSupport() {
    }

    static Path resolvePath(WorkspaceService workspaceService, String agentId, String relativePath) {
        Path root = workspaceService.getWorkspaceRoot(agentId);
        String value = relativePath == null || relativePath.isBlank() ? "." : relativePath;
        Path candidate = parsePath(value);
        Path target = candidate != null && candidate.isAbsolute()
                ? candidate.normalize()
                : root.resolve(value).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("workspace path escape detected: " + relativePath);
        }
        return target;
    }

    private static Path parsePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}
