package com.janyee.agent.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface WorkspaceService {
    List<String> listAgentIds();

    Path getWorkspaceRoot(String agentId);

    Optional<String> readAgentFile(String agentId, String fileName);

    List<WorkspaceKnowledgeFile> listKnowledgeFiles(String agentId);

    WorkspaceToolPolicy getToolPolicy(String agentId);
}
