package com.janyee.agent.infra.agent;

import com.janyee.agent.domain.AgentDefinition;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.infra.persistence.entity.AgentDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.AgentDefinitionRepository;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DB-backed replacement for {@code InMemoryAgentDefinitionService}.
 *
 * <p>Plan A Phase 2 moves agent definitions out of {@code workspaces/&lt;agent&gt;/*.md} files
 * into the {@code agent_definition} table so the frontend can CRUD them and operators don't
 * need shell access to the server. The workspace directory is retained as a runtime scratchpad
 * (artifacts/, temp/, knowledge/*.md).</p>
 *
 * <p>Fallback behaviour: if the DB has no row for the requested agentId but the workspace
 * directory still exists, we synthesise an {@link AgentDefinition} from the files — this
 * prevents breakage during the migration window where V16 hasn't yet upserted a row.</p>
 */
@Service
@Primary
public class JpaAgentDefinitionService implements AgentDefinitionService {

    private final AgentDefinitionRepository repository;
    private final AgentPlatformProperties properties;
    private final WorkspaceService workspaceService;

    public JpaAgentDefinitionService(
            AgentDefinitionRepository repository,
            AgentPlatformProperties properties,
            WorkspaceService workspaceService
    ) {
        this.repository = repository;
        this.properties = properties;
        this.workspaceService = workspaceService;
    }

    @Override
    public AgentDefinition getAgent(String agentId) {
        String resolved = agentId == null || agentId.isBlank() ? properties.defaultAgentId() : agentId;
        AgentDefinitionEntity entity = repository.findById(resolved).orElse(null);
        if (entity != null && entity.isEnabled()) {
            return toDefinition(entity);
        }
        // Fallback: DB empty (pre-migration) — read files so system keeps working.
        return fallbackFromWorkspace(resolved);
    }

    @Override
    public List<AgentDefinition> listAgents() {
        List<AgentDefinitionEntity> rows = repository.findByEnabledTrueOrderByDisplayNameAsc();
        if (rows.isEmpty()) {
            // First boot / empty DB → expose whatever is on disk so the UI isn't blank.
            return workspaceService.listAgentIds().stream()
                    .map(this::fallbackFromWorkspace)
                    .toList();
        }
        return rows.stream().map(this::toDefinition).toList();
    }

    private AgentDefinition toDefinition(AgentDefinitionEntity entity) {
        String workspacePath = workspaceService.getWorkspaceRoot(entity.getAgentId()).toString();
        String systemPrompt = entity.getSystemPrompt().isBlank()
                ? "You are a local-first OpenClaw-like Java agent."
                : entity.getSystemPrompt();
        return new AgentDefinition(
                entity.getAgentId(),
                entity.getDisplayName(),
                systemPrompt,
                workspacePath,
                entity.getAgentMarkdown(),
                entity.getMemoryMarkdown()
        );
    }

    private AgentDefinition fallbackFromWorkspace(String agentId) {
        String agentMarkdown = workspaceService.readAgentFile(agentId, "AGENT.md").orElse("");
        String displayName = extractHeader(agentMarkdown).orElse(agentId);
        String systemPrompt = workspaceService.readAgentFile(agentId, "SOUL.md")
                .or(() -> workspaceService.readAgentFile(agentId, "AGENT.md"))
                .orElse("You are a local-first OpenClaw-like Java agent.");
        String memoryMarkdown = workspaceService.readAgentFile(agentId, "MEMORY.md").orElse("");
        return new AgentDefinition(
                agentId,
                displayName,
                systemPrompt,
                workspaceService.getWorkspaceRoot(agentId).toString(),
                agentMarkdown,
                memoryMarkdown
        );
    }

    private java.util.Optional<String> extractHeader(String markdown) {
        if (markdown == null) {
            return java.util.Optional.empty();
        }
        return markdown.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst();
    }
}
