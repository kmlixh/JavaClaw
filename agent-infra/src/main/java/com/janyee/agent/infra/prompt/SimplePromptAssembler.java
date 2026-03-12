package com.janyee.agent.infra.prompt;

import com.janyee.agent.domain.AgentDefinition;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.memory.MemoryQuery;
import com.janyee.agent.memory.MemoryService;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.workspace.WorkspaceKnowledgeFile;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SimplePromptAssembler implements PromptAssembler {

    private final AgentDefinitionService agentDefinitionService;
    private final MemoryService memoryService;
    private final WorkspaceService workspaceService;

    public SimplePromptAssembler(
            AgentDefinitionService agentDefinitionService,
            MemoryService memoryService,
            WorkspaceService workspaceService
    ) {
        this.agentDefinitionService = agentDefinitionService;
        this.memoryService = memoryService;
        this.workspaceService = workspaceService;
    }

    @Override
    public PromptContext assemble(RunRequest request) {
        AgentDefinition agent = agentDefinitionService.getAgent(request.agentId());
        String memorySection = memoryService.retrieve(new MemoryQuery(agent.id(), request.message())).stream()
                .map(memory -> "- " + memory.content())
                .collect(Collectors.joining("\n"));
        String agentFile = workspaceService.readAgentFile(agent.id(), "AGENT.md").orElse("(missing)");
        String soulFile = workspaceService.readAgentFile(agent.id(), "SOUL.md").orElse("(missing)");
        String explicitMemory = workspaceService.readAgentFile(agent.id(), "MEMORY.md").orElse("(missing)");
        List<WorkspaceKnowledgeFile> knowledgeFiles = workspaceService.listKnowledgeFiles(agent.id());
        String knowledgeSection = knowledgeFiles.isEmpty()
                ? "(none)"
                : knowledgeFiles.stream()
                        .map(file -> "## " + file.relativePath() + "\n" + truncate(file.content(), 1200))
                        .collect(Collectors.joining("\n\n"));
        String assembledPrompt = """
                Agent: %s
                Workspace: %s
                AGENT.md:
                %s
                SOUL.md:
                %s
                MEMORY.md:
                %s
                knowledge/:
                %s
                User: %s
                Message: %s
                Relevant memory:
                %s
                """.formatted(
                agent.displayName(),
                agent.workspacePath(),
                agentFile,
                soulFile,
                explicitMemory,
                knowledgeSection,
                request.userId(),
                request.message(),
                memorySection.isBlank() ? "(none)" : memorySection
        );
        return new PromptContext(agent.systemPrompt(), assembledPrompt);
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }
}
