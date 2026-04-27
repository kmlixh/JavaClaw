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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        // 必须传 sessionId —— 否则 retrieve 会把其他会话残留的 run_summary（含数字）拉进来，
        // LLM 会把它们当成自己查到的数据写进新报告。这是一个真实发生过的 bug。
        String memorySection = memoryService.retrieve(
                new MemoryQuery(agent.id(), request.sessionId(), request.message())).stream()
                .map(memory -> "- " + memory.content())
                .collect(Collectors.joining("\n"));
        // Plan A Phase 2：agent 元数据从 agent_definition 表来；文件降级为 fallback。
        // agentMarkdown/memoryMarkdown 之前塞进 AgentDefinition 时已经做过 "DB 优先" 的选择。
        String agentFile = !agent.agentMarkdown().isBlank()
                ? agent.agentMarkdown()
                : workspaceService.readAgentFile(agent.id(), "AGENT.md").orElse("(missing)");
        String soulFile = !agent.systemPrompt().isBlank()
                ? agent.systemPrompt()
                : workspaceService.readAgentFile(agent.id(), "SOUL.md").orElse("(missing)");
        String explicitMemory = !agent.memoryMarkdown().isBlank()
                ? agent.memoryMarkdown()
                : workspaceService.readAgentFile(agent.id(), "MEMORY.md").orElse("(missing)");
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
        String nowStamp = currentSystemTimestamp();
        String assembledPrompt = """
                [全局语言规则]
                所有对外输出（assistant 文本回复、plan.create 的 title/step title/toolHint/expectedOutput、plan.update 的 resultNote、db.query 的 SQL 注释、artifact.markdown 的正文和每行中文说明、最终报告、错误解释）**必须使用简体中文**。英文只允许出现在：
                - 工具名（db.query / plan.create 等不翻译）
                - SQL 关键字和表/列名（保持大小写原样）
                - JSON 字段名（id/title/status 等不翻译）
                - URL / 文件名 / 程序标识符
                不要在最终文本里夹杂日语、繁体、英文整句，除非是专有名词。

                [系统时间]
                当前服务器本地时间：%s（精确到秒）。
                当报告 / artifact / assistant 文本需要填写"生成时间""报告时间""数据截止时间"等字段时，**必须使用这个时间戳**（可以按需写成 "YYYY-MM-DD HH:mm:ss" 或 "YYYY年M月D日 HH:mm" 等可读形式），不要写"2026 年"这种粗粒度占位，也不要从你自己的训练数据推断时间。

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
                Relevant memory (仅本会话历史 + 用户 pin 的长期备注；**不要引用这里面的数字作为"当前查询结果"，必须用 db.query 重新取数**):
                %s
                """.formatted(
                nowStamp,
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

    /**
     * 返回形如 "2026-04-22 17:03:47 +0800 (CST)" 的时间戳 —— 带时区和秒，避免 LLM 拿到
     * 模糊的"2026 年"或者凭空臆造时间。精度到秒就够报告用了；如果需要更高精度再改成毫秒。
     */
    private String currentSystemTimestamp() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z (z)");
        return now.atZone(zone).format(fmt);
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
