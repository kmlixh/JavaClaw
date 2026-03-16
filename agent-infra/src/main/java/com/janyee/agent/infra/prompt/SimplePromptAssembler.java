package com.janyee.agent.infra.prompt;

import com.janyee.agent.domain.AgentDefinition;
import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.infra.persistence.repository.ToolDefinitionRepository;
import com.janyee.agent.memory.MemoryQuery;
import com.janyee.agent.memory.MemoryService;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.runtime.skill.SkillDefinitionService;
import com.janyee.agent.runtime.skill.SkillPrompt;
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
    private final SkillDefinitionService skillDefinitionService;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;

    public SimplePromptAssembler(
            AgentDefinitionService agentDefinitionService,
            MemoryService memoryService,
            WorkspaceService workspaceService,
            SkillDefinitionService skillDefinitionService,
            KnowledgeEntryRepository knowledgeEntryRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            SkillDefinitionRepository skillDefinitionRepository
    ) {
        this.agentDefinitionService = agentDefinitionService;
        this.memoryService = memoryService;
        this.workspaceService = workspaceService;
        this.skillDefinitionService = skillDefinitionService;
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.skillDefinitionRepository = skillDefinitionRepository;
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
        List<SkillPrompt> skills = skillDefinitionService.listEnabledSkillPrompts(agent.id());
        String knowledgeSection = knowledgeFiles.isEmpty()
                ? "(none)"
                : knowledgeFiles.stream()
                        .map(file -> "## " + file.relativePath() + "\n" + truncate(file.content(), 1200))
                        .collect(Collectors.joining("\n\n"));
        String skillSection = skills.isEmpty()
                ? "(none)"
                : skills.stream()
                        .map(skill -> "## " + skill.skillName() + "\n"
                                + (skill.description() == null || skill.description().isBlank() ? "" : skill.description() + "\n")
                                + truncate(skill.promptTemplate(), 1200))
                        .collect(Collectors.joining("\n\n"));
        String explicitReferenceSection = renderExplicitReferences(request.references());
        String attachmentSection = renderAttachments(request.attachments());
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
                skills:
                %s
                explicitly selected context:
                %s
                attachments:
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
                skillSection,
                explicitReferenceSection,
                attachmentSection,
                request.userId(),
                request.message(),
                memorySection.isBlank() ? "(none)" : memorySection
        );
        return new PromptContext(agent.systemPrompt(), assembledPrompt);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }

    private String renderExplicitReferences(List<ChatContextReference> references) {
        if (references == null || references.isEmpty()) {
            return "(none)";
        }
        return references.stream()
                .map(this::renderReference)
                .collect(Collectors.joining("\n\n"));
    }

    private String renderReference(ChatContextReference reference) {
        return switch (reference.type()) {
            case "knowledge" -> knowledgeEntryRepository.findById(reference.id())
                    .map(item -> "## knowledge: " + item.getTitle() + "\n" + truncate(item.getContent(), 1600))
                    .orElse("## knowledge: missing " + reference.id());
            case "tool" -> toolDefinitionRepository.findById(reference.id())
                    .map(item -> "## tool: " + item.getDisplayName() + " (" + item.getToolName() + ")\n"
                            + truncate(item.getDescription(), 500) + "\nSchema:\n" + truncate(item.getSchemaJson(), 1200))
                    .orElse("## tool: missing " + reference.id());
            case "skill" -> skillDefinitionRepository.findById(reference.id())
                    .map(item -> "## skill: " + item.getSkillName() + "\n"
                            + truncate(item.getDescription(), 500) + "\n" + truncate(item.getPromptTemplate(), 1600))
                    .orElse("## skill: missing " + reference.id());
            default -> "## " + reference.type() + ": " + (reference.label() != null ? reference.label() : reference.id());
        };
    }

    private String renderAttachments(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "(none)";
        }
        return attachments.stream()
                .map(item -> "## " + item.name() + " [" + (item.contentType() == null ? "text/plain" : item.contentType()) + "]\n"
                        + truncate(item.content(), 3000))
                .collect(Collectors.joining("\n\n"));
    }
}
