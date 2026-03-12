package com.janyee.agent.workspace;

import java.util.Set;

public record WorkspaceToolPolicy(
        Mode mode,
        Set<String> allowedTools,
        Set<String> deniedTools
) {
    public enum Mode {
        ALLOW_ALL,
        ALLOW_LIST
    }

    public static WorkspaceToolPolicy allowAll() {
        return new WorkspaceToolPolicy(Mode.ALLOW_ALL, Set.of(), Set.of());
    }

    public boolean isAllowed(String toolName) {
        String normalized = normalize(toolName);
        if (deniedTools.contains(normalized)) {
            return false;
        }
        if (mode == Mode.ALLOW_LIST) {
            return allowedTools.contains(normalized);
        }
        return true;
    }

    public static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase();
    }
}
