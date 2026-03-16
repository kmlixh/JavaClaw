package com.janyee.agent.infra.admin;

import com.janyee.agent.infra.persistence.entity.KnowledgeEntryEntity;
import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.entity.ToolDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.infra.persistence.repository.ToolDefinitionRepository;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.KnowledgeEntryCommand;
import com.janyee.agent.runtime.admin.KnowledgeEntryView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import com.janyee.agent.runtime.admin.ToolDefinitionCommand;
import com.janyee.agent.runtime.admin.ToolDefinitionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class JpaAdminCatalogService implements AdminCatalogService {

    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;

    public JpaAdminCatalogService(
            KnowledgeEntryRepository knowledgeEntryRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            SkillDefinitionRepository skillDefinitionRepository
    ) {
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.skillDefinitionRepository = skillDefinitionRepository;
    }

    @Override
    public List<KnowledgeEntryView> listKnowledgeEntries(String agentId) {
        return knowledgeEntryRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .map(this::toKnowledgeView)
                .toList();
    }

    @Override
    @Transactional
    public KnowledgeEntryView saveKnowledgeEntry(KnowledgeEntryCommand command) {
        KnowledgeEntryEntity entity = command.id() == null || command.id().isBlank()
                ? new KnowledgeEntryEntity()
                : knowledgeEntryRepository.findById(command.id()).orElseGet(KnowledgeEntryEntity::new);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setVersion(1);
        } else {
            entity.setVersion(entity.getVersion() + 1);
        }
        entity.setAgentId(command.agentId());
        entity.setTitle(command.title());
        entity.setContent(command.content());
        entity.setContentType(defaultIfBlank(command.contentType(), "markdown"));
        entity.setSource(defaultIfBlank(command.source(), "database"));
        entity.setTagsJson(command.tagsJson());
        entity.setEnabled(command.enabled());
        return toKnowledgeView(knowledgeEntryRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteKnowledgeEntry(String id) {
        knowledgeEntryRepository.deleteById(id);
    }

    @Override
    public List<ToolDefinitionView> listToolDefinitions(String agentId) {
        return toolDefinitionRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .map(this::toToolView)
                .toList();
    }

    @Override
    @Transactional
    public ToolDefinitionView saveToolDefinition(ToolDefinitionCommand command) {
        ToolDefinitionEntity entity = command.id() == null || command.id().isBlank()
                ? new ToolDefinitionEntity()
                : toolDefinitionRepository.findById(command.id()).orElseGet(ToolDefinitionEntity::new);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setVersion(1);
        } else {
            entity.setVersion(entity.getVersion() + 1);
        }
        entity.setAgentId(command.agentId());
        entity.setToolName(command.toolName());
        entity.setDisplayName(command.displayName());
        entity.setDescription(command.description());
        entity.setSchemaJson(command.schemaJson());
        entity.setToolType(defaultIfBlank(command.toolType(), "builtin"));
        entity.setConfigJson(command.configJson());
        entity.setEnabled(command.enabled());
        entity.setApprovalRequired(command.approvalRequired());
        return toToolView(toolDefinitionRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteToolDefinition(String id) {
        toolDefinitionRepository.deleteById(id);
    }

    @Override
    public List<SkillDefinitionView> listSkillDefinitions(String agentId) {
        return skillDefinitionRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .map(this::toSkillView)
                .toList();
    }

    @Override
    @Transactional
    public SkillDefinitionView saveSkillDefinition(SkillDefinitionCommand command) {
        SkillDefinitionEntity entity = command.id() == null || command.id().isBlank()
                ? new SkillDefinitionEntity()
                : skillDefinitionRepository.findById(command.id()).orElseGet(SkillDefinitionEntity::new);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setVersion(1);
        } else {
            entity.setVersion(entity.getVersion() + 1);
        }
        entity.setAgentId(command.agentId());
        entity.setSkillName(command.skillName());
        entity.setDescription(command.description());
        entity.setPromptTemplate(command.promptTemplate());
        entity.setConfigJson(command.configJson());
        entity.setEnabled(command.enabled());
        return toSkillView(skillDefinitionRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteSkillDefinition(String id) {
        skillDefinitionRepository.deleteById(id);
    }

    private KnowledgeEntryView toKnowledgeView(KnowledgeEntryEntity entity) {
        return new KnowledgeEntryView(
                entity.getId(),
                entity.getAgentId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getContentType(),
                entity.getSource(),
                entity.getTagsJson(),
                entity.isEnabled(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ToolDefinitionView toToolView(ToolDefinitionEntity entity) {
        return new ToolDefinitionView(
                entity.getId(),
                entity.getAgentId(),
                entity.getToolName(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getSchemaJson(),
                entity.getToolType(),
                entity.getConfigJson(),
                entity.isEnabled(),
                entity.isApprovalRequired(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private SkillDefinitionView toSkillView(SkillDefinitionEntity entity) {
        return new SkillDefinitionView(
                entity.getId(),
                entity.getAgentId(),
                entity.getSkillName(),
                entity.getDescription(),
                entity.getPromptTemplate(),
                entity.getConfigJson(),
                entity.isEnabled(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
