package com.janyee.agent.infra.agent;

import com.janyee.agent.domain.AgentDefinition;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InMemoryAgentDefinitionService implements AgentDefinitionService {

    private final AgentPlatformProperties properties;
    private final WorkspaceService workspaceService;

    public InMemoryAgentDefinitionService(AgentPlatformProperties properties, WorkspaceService workspaceService) {
        this.properties = properties;
        this.workspaceService = workspaceService;
    }

    @Override
    public AgentDefinition getAgent(String agentId) {
        String resolvedAgentId = agentId == null || agentId.isBlank() ? properties.defaultAgentId() : agentId;
        return buildDefinition(resolvedAgentId);
    }

    @Override
    public List<AgentDefinition> listAgents() {
        List<String> agentIds = workspaceService.listAgentIds();
        if (agentIds.isEmpty()) {
            return List.of(buildDefinition(properties.defaultAgentId()));
        }
        return agentIds.stream()
                .map(this::buildDefinition)
                .toList();
    }

    private java.util.Optional<String> extractHeader(String markdown) {
        return markdown.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst();
    }

    private AgentDefinition buildDefinition(String agentId) {
        String agentMarkdown = workspaceService.readAgentFile(agentId, "AGENT.md").orElse("");
        String displayName = extractHeader(agentMarkdown).orElse(agentId);
        String systemPrompt = workspaceService.readAgentFile(agentId, "SOUL.md")
                .or(() -> workspaceService.readAgentFile(agentId, "AGENT.md"))
                .orElse("You are a local-first OpenClaw-like Java agent.");
        return new AgentDefinition(
                agentId,
                displayName,
                systemPrompt,
                workspaceService.getWorkspaceRoot(agentId).toString()
        );
    }
}
