package com.janyee.agent.infra.admin;

import com.janyee.agent.infra.persistence.entity.AgentDefinitionEntity;
import com.janyee.agent.infra.persistence.entity.DbDatasourceEntity;
import com.janyee.agent.infra.persistence.entity.KnowledgeEntryEntity;
import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import com.janyee.agent.infra.persistence.entity.SkillAgentBindingEntity;
import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import com.janyee.agent.infra.persistence.entity.ToolDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.AgentDefinitionRepository;
import com.janyee.agent.infra.persistence.repository.DbDatasourceRepository;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import com.janyee.agent.infra.persistence.repository.LlmProviderConfigRepository;
import com.janyee.agent.infra.persistence.repository.MemoryNoteRepository;
import com.janyee.agent.infra.persistence.repository.SkillAgentBindingRepository;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.infra.persistence.repository.ToolDefinitionRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserAgentBindingRepository;
import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.ResourceScopeFilter;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import com.janyee.agent.infra.persistence.entity.auth.UserAgentBindingEntity;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.DatasourceCommand;
import com.janyee.agent.runtime.admin.DatasourceView;
import com.janyee.agent.runtime.admin.KnowledgeEntryCommand;
import com.janyee.agent.runtime.admin.KnowledgeEntryView;
import com.janyee.agent.runtime.admin.AgentDefinitionCommand;
import com.janyee.agent.runtime.admin.AgentDefinitionView;
import com.janyee.agent.runtime.admin.LlmConfigCommand;
import com.janyee.agent.runtime.admin.LlmConfigView;
import com.janyee.agent.runtime.admin.MemoryNoteCommand;
import com.janyee.agent.runtime.admin.MemoryNoteView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import com.janyee.agent.runtime.admin.ToolDefinitionCommand;
import com.janyee.agent.runtime.admin.ToolDefinitionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JpaAdminCatalogService implements AdminCatalogService {

    private final LlmProviderConfigRepository llmProviderConfigRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final SkillAgentBindingRepository skillAgentBindingRepository;
    private final DbDatasourceRepository dbDatasourceRepository;
    private final MemoryNoteRepository memoryNoteRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final UserAgentBindingRepository userAgentBindingRepository;
    private final ObjectMapper objectMapper;
    private final AgentPlatformProperties properties;

    public JpaAdminCatalogService(
            LlmProviderConfigRepository llmProviderConfigRepository,
            KnowledgeEntryRepository knowledgeEntryRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            SkillDefinitionRepository skillDefinitionRepository,
            SkillAgentBindingRepository skillAgentBindingRepository,
            DbDatasourceRepository dbDatasourceRepository,
            MemoryNoteRepository memoryNoteRepository,
            AgentDefinitionRepository agentDefinitionRepository,
            UserAgentBindingRepository userAgentBindingRepository,
            ObjectMapper objectMapper,
            AgentPlatformProperties properties
    ) {
        this.llmProviderConfigRepository = llmProviderConfigRepository;
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.skillDefinitionRepository = skillDefinitionRepository;
        this.skillAgentBindingRepository = skillAgentBindingRepository;
        this.dbDatasourceRepository = dbDatasourceRepository;
        this.memoryNoteRepository = memoryNoteRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.userAgentBindingRepository = userAgentBindingRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<LlmConfigView> listLlmConfigs() {
        return llmProviderConfigRepository.findAllByOrderByDisplayNameAsc().stream()
                .map(this::toLlmConfigView)
                .toList();
    }

    @Override
    @Transactional
    public LlmConfigView saveLlmConfig(LlmConfigCommand command) {
        LlmProviderConfigEntity entity = command.id() == null || command.id().isBlank()
                ? new LlmProviderConfigEntity()
                : llmProviderConfigRepository.findById(command.id()).orElseGet(LlmProviderConfigEntity::new);
        if (entity.getId() == null) {
            entity.setId(command.id() == null || command.id().isBlank() ? UUID.randomUUID().toString() : command.id());
        }
        entity.setProvider(defaultIfBlank(command.provider(), "openai-compatible"));
        entity.setDisplayName(defaultIfBlank(command.displayName(), entity.getId()));
        ModelMappingResolved modelMappingResolved = resolveModelMapping(command.model(), command.modelMappingJson());
        entity.setModel(defaultIfBlank(modelMappingResolved.primaryModel(), "gpt-4.1"));
        entity.setModelMappingJson(modelMappingResolved.modelMappingJson());
        String configuredBaseUrl = command.baseUrl();
        String unifiedBaseUrl = properties != null && properties.llm() != null
                ? properties.llm().baseUrl()
                : null;
        String unifiedChatPath = properties != null && properties.llm() != null
                ? properties.llm().chatPath()
                : null;
        entity.setBaseUrl(defaultIfBlank(configuredBaseUrl, defaultIfBlank(unifiedBaseUrl, "http://localhost:11434/v1")));
        entity.setApiKey(command.apiKey() == null ? "" : command.apiKey());
        entity.setChatPath(defaultIfBlank(unifiedChatPath, "/chat/completions"));
        entity.setStreamEnabled(command.stream());
        entity.setEnabled(command.enabled());
        entity.setDefaultConfig(command.defaultConfig());

        if (entity.isDefaultConfig()) {
            clearOtherDefaultFlags(entity.getId());
        }

        return toLlmConfigView(llmProviderConfigRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteLlmConfig(String id) {
        llmProviderConfigRepository.deleteById(id);
    }

    @Override
    public List<KnowledgeEntryView> listKnowledgeEntries(String agentId) {
        AuthPrincipal principal = SecurityContextHolder.current();
        return knowledgeEntryRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .filter(e -> principal.anonymous()
                        || ResourceScopeFilter.matches(
                                e.getScopeType(), e.getScopeTenantId(), e.getScopeUserId(), principal))
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
        applyScopeOnNew(entity);
        return toKnowledgeView(knowledgeEntryRepository.save(entity));
    }

    /** 仅当 entity 是新创建(scopeType 还为空)时按 principal 推断填入,编辑路径保留原值。 */
    private void applyScopeOnNew(KnowledgeEntryEntity e) {
        if (e.getScopeType() != null && !e.getScopeType().isBlank()) return;
        InferredScope s = inferScope();
        e.setScopeType(s.type); e.setScopeTenantId(s.tenantId); e.setScopeUserId(s.userId); e.setAppId(s.appId);
    }
    private void applyScopeOnNew(SkillDefinitionEntity e) {
        if (e.getScopeType() != null && !e.getScopeType().isBlank()) return;
        InferredScope s = inferScope();
        e.setScopeType(s.type); e.setScopeTenantId(s.tenantId); e.setScopeUserId(s.userId); e.setAppId(s.appId);
    }
    private void applyScopeOnNew(DbDatasourceEntity e) {
        if (e.getScopeType() != null && !e.getScopeType().isBlank()) return;
        InferredScope s = inferScope();
        e.setScopeType(s.type); e.setScopeTenantId(s.tenantId); e.setScopeUserId(s.userId); e.setAppId(s.appId);
    }
    private void applyScopeOnNew(MemoryNoteEntity e) {
        if (e.getScopeType() != null && !e.getScopeType().isBlank()) return;
        InferredScope s = inferScope();
        e.setScopeType(s.type); e.setScopeTenantId(s.tenantId); e.setScopeUserId(s.userId); e.setAppId(s.appId);
    }
    private void applyScopeOnNew(AgentDefinitionEntity e) {
        if (e.getVisibility() != null && !e.getVisibility().isBlank()) return;
        InferredScope s = inferScope();
        e.setVisibility(s.type); e.setScopeTenantId(s.tenantId); e.setScopeUserId(s.userId); e.setAppId(s.appId);
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
        // 有 agentId 过滤时只返回绑到这个 agent 的 skill;没给就全量(admin 总览)。
        List<SkillDefinitionEntity> entities = (agentId == null || agentId.isBlank())
                ? skillDefinitionRepository.findAll()
                : skillDefinitionRepository.findByAgentIdOrderByUpdatedAtDesc(agentId);
        AuthPrincipal principal = SecurityContextHolder.current();
        return entities.stream()
                .filter(e -> principal.anonymous()
                        || ResourceScopeFilter.matches(
                                e.getScopeType(), e.getScopeTenantId(), e.getScopeUserId(), principal))
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
        // 归一化目标 agent 列表:优先使用 agentIds,否则退回 legacy 单字段 agentId。
        List<String> targetAgents = normalizeAgentIds(command.agentIds(), command.agentId());
        // legacy agent_id 列填第一个,方便旧 client 还能读到"某个 agent"的概念;
        // 但真实 M:N 关系由 binding 表保存。
        entity.setAgentId(targetAgents.isEmpty() ? null : targetAgents.get(0));
        entity.setSkillName(command.skillName());
        entity.setDescription(command.description());
        entity.setPromptTemplate(command.promptTemplate());
        entity.setConfigJson(command.configJson());
        entity.setTriggerKeywords(command.triggerKeywords());
        entity.setEnabled(command.enabled());
        applyScopeOnNew(entity);
        SkillDefinitionEntity saved = skillDefinitionRepository.save(entity);

        // 重置 binding:先删,再按新 agentIds 插入。用 deleteBySkillId 而不是级联,
        // 是因为我们要精确控制"这一次 save 以后它的 binding 就是这些"。
        skillAgentBindingRepository.deleteBySkillId(saved.getId());
        skillAgentBindingRepository.flush();
        for (String agentId : targetAgents) {
            skillAgentBindingRepository.save(new SkillAgentBindingEntity(saved.getId(), agentId));
        }

        return toSkillView(saved);
    }

    @Override
    @Transactional
    public void deleteSkillDefinition(String id) {
        // FK ON DELETE CASCADE 会连带清理 binding,但显式先删能避免依赖 DDL 的隐式行为。
        skillAgentBindingRepository.deleteBySkillId(id);
        skillDefinitionRepository.deleteById(id);
    }

    /**
     * 按 principal 推断新建资源的 scope:
     *   - 匿名 / 系统超管(有 session.read.all):SYSTEM, system-default
     *   - 租户管理员 (有 user.manage 但不是超管):TENANT, principal.tenantId
     *   - 普通用户:USER, principal.userId
     * 已有 scopeType 的 entity 不覆盖,避免编辑时把超管手动设的 SYSTEM 资源被改回 TENANT。
     */
    private static class InferredScope {
        String type; String tenantId; String userId; String appId;
    }
    private InferredScope inferScope() {
        AuthPrincipal p = SecurityContextHolder.current();
        InferredScope s = new InferredScope();
        if (p == null || p.anonymous() || p.permissions().contains("session.read.all")) {
            s.type = "SYSTEM"; s.appId = "system-default";
        } else if (p.permissions().contains("user.manage") || p.permissions().contains("skill.manage")) {
            // skill.manage 这类资源管理权限做兜底:租户管理员必有,普通用户也有(资源会落到 TENANT 里供后续用 USER scope 覆盖)
            s.type = "TENANT"; s.tenantId = p.tenantId(); s.appId = p.appId();
            // 普通用户(没 user.manage)创建资源应该是 USER scope,而非 TENANT
            if (!p.permissions().contains("user.manage")) {
                s.type = "USER"; s.tenantId = null; s.userId = p.userId();
            }
        } else {
            s.type = "USER"; s.userId = p.userId(); s.appId = p.appId();
        }
        return s;
    }

    /** 把 agentIds + legacy agentId 归一化成一条有序去重列表。agentIds 优先,空了才退回单字段。 */
    private List<String> normalizeAgentIds(List<String> agentIds, String legacyAgentId) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (agentIds != null) {
            for (String id : agentIds) {
                if (id != null && !id.isBlank()) {
                    seen.add(id.trim());
                }
            }
        }
        if (seen.isEmpty() && legacyAgentId != null && !legacyAgentId.isBlank()) {
            seen.add(legacyAgentId.trim());
        }
        return new ArrayList<>(seen);
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
        List<String> boundAgentIds = skillAgentBindingRepository.findBySkillId(entity.getId()).stream()
                .map(SkillAgentBindingEntity::getAgentId)
                .toList();
        return new SkillDefinitionView(
                entity.getId(),
                entity.getAgentId(),
                boundAgentIds,
                entity.getSkillName(),
                entity.getDescription(),
                entity.getPromptTemplate(),
                entity.getConfigJson(),
                entity.getTriggerKeywords(),
                entity.isEnabled(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private LlmConfigView toLlmConfigView(LlmProviderConfigEntity entity) {
        return new LlmConfigView(
                entity.getId(),
                entity.getProvider(),
                entity.getDisplayName(),
                entity.getModel(),
                normalizeModelMappingJson(entity.getModel(), entity.getModelMappingJson()),
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getChatPath(),
                entity.isStreamEnabled(),
                entity.isEnabled(),
                entity.isDefaultConfig(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ModelMappingResolved resolveModelMapping(String fallbackModel, String modelMappingJson) {
        String normalized = normalizeModelMappingJson(fallbackModel, modelMappingJson);
        String primary = extractPrimaryModel(normalized);
        if (isBlank(primary)) {
            primary = defaultIfBlank(fallbackModel, "gpt-4.1");
            normalized = normalizeModelMappingJson(primary, normalized);
        }
        return new ModelMappingResolved(primary, normalized);
    }

    private String normalizeModelMappingJson(String fallbackModel, String modelMappingJson) {
        List<Map<String, String>> models = new ArrayList<>();
        JsonNode root = parseModelRoot(modelMappingJson);
        if (root == null) {
            return toModelMappingJsonWithFallback(fallbackModel);
        }
        JsonNode modelArray = root.path("models");
        if (modelArray.isArray()) {
            for (JsonNode item : modelArray) {
                String displayName = text(item.path("displayName"));
                String apiModel = text(item.path("apiModel"));
                if (isBlank(apiModel) && !isBlank(displayName)) {
                    apiModel = displayName;
                }
                if (isBlank(displayName) && !isBlank(apiModel)) {
                    displayName = apiModel;
                }
                if (isBlank(apiModel)) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("displayName", displayName);
                row.put("apiModel", apiModel);
                models.add(row);
            }
        }
        if (models.isEmpty()) {
            String model = defaultIfBlank(fallbackModel, "");
            if (!isBlank(model)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("displayName", model);
                row.put("apiModel", model);
                models.add(row);
            }
        }
        return writeModelMappings(models);
    }

    private JsonNode parseModelRoot(String modelMappingJson) {
        if (isBlank(modelMappingJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(modelMappingJson);
            if (node == null) {
                return null;
            }
            if (node.isArray()) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("models", node);
                return objectMapper.valueToTree(wrapper);
            }
            return node;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String toModelMappingJsonWithFallback(String fallbackModel) {
        String model = defaultIfBlank(fallbackModel, "");
        if (isBlank(model)) {
            return "{\"models\":[]}";
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("displayName", model);
        row.put("apiModel", model);
        return writeModelMappings(List.of(row));
    }

    private String writeModelMappings(List<Map<String, String>> models) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("models", models);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{\"models\":[]}";
        }
    }

    private String extractPrimaryModel(String modelMappingJson) {
        JsonNode root = parseModelRoot(modelMappingJson);
        if (root == null || !root.path("models").isArray()) {
            return "";
        }
        for (JsonNode item : root.path("models")) {
            String apiModel = text(item.path("apiModel"));
            if (!isBlank(apiModel)) {
                return apiModel;
            }
        }
        return "";
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        String value = node.asText("");
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ModelMappingResolved(String primaryModel, String modelMappingJson) {}

    private void clearOtherDefaultFlags(String currentId) {
        llmProviderConfigRepository.findAll().stream()
                .filter(item -> !item.getId().equals(currentId) && item.isDefaultConfig())
                .forEach(item -> item.setDefaultConfig(false));
    }

    @Override
    public List<DatasourceView> listDatasources() {
        AuthPrincipal principal = SecurityContextHolder.current();
        List<DbDatasourceEntity> all = dbDatasourceRepository.findAll();
        List<DatasourceView> views = new ArrayList<>(all.size());
        for (DbDatasourceEntity entity : all) {
            if (!principal.anonymous()
                    && !ResourceScopeFilter.matches(
                            entity.getScopeType(), entity.getScopeTenantId(), entity.getScopeUserId(), principal)) {
                continue;
            }
            views.add(toDatasourceView(entity));
        }
        return views;
    }

    @Override
    @Transactional
    public DatasourceView saveDatasource(DatasourceCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("datasource command is required");
        }
        if (isBlank(command.jdbcUrl())) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        if (isBlank(command.username())) {
            throw new IllegalArgumentException("username is required");
        }
        String id = isBlank(command.id()) ? UUID.randomUUID().toString() : command.id().trim();
        DbDatasourceEntity entity = dbDatasourceRepository.findById(id).orElseGet(DbDatasourceEntity::new);
        boolean isNew = entity.getId() == null;
        entity.setId(id);
        entity.setDisplayName(isBlank(command.displayName()) ? id : command.displayName().trim());
        entity.setJdbcUrl(command.jdbcUrl().trim());
        entity.setUsername(command.username().trim());
        // Password: keep existing on blank, overwrite on non-blank. Admins can rotate without
        // re-entering every other field.
        if (!isBlank(command.password())) {
            entity.setPassword(command.password());
        } else if (isNew) {
            throw new IllegalArgumentException("password is required for a new datasource");
        }
        entity.setDialect(isBlank(command.dialect()) ? null : command.dialect().trim());
        entity.setDescription(isBlank(command.description()) ? null : command.description());
        entity.setEnabled(command.enabled());
        applyScopeOnNew(entity);
        return toDatasourceView(dbDatasourceRepository.save(entity));
    }

    @Override
    public void deleteDatasource(String id) {
        if (isBlank(id)) {
            return;
        }
        dbDatasourceRepository.deleteById(id.trim());
    }

    private DatasourceView toDatasourceView(DbDatasourceEntity entity) {
        return new DatasourceView(
                entity.getId(),
                entity.getDisplayName(),
                entity.getJdbcUrl(),
                entity.getUsername(),
                entity.getPassword() != null && !entity.getPassword().isEmpty(),
                entity.getDialect(),
                entity.getDescription(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    // ── Memory notes (Plan A Phase 1) ──────────────────────────────────────

    @Override
    public List<MemoryNoteView> listMemoryNotes(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        AuthPrincipal principal = SecurityContextHolder.current();
        // Newest 40 notes across all scopes so the admin UI can see session/agent/global together.
        return memoryNoteRepository.findTop40ByAgentIdAndScopeInOrderByCreatedAtDesc(
                        agentId, List.of("session", "agent", "global"))
                .stream()
                .filter(e -> principal.anonymous()
                        || ResourceScopeFilter.matches(
                                e.getScopeType(), e.getScopeTenantId(), e.getScopeUserId(), principal))
                .map(this::toMemoryNoteView)
                .toList();
    }

    @Override
    @Transactional
    public MemoryNoteView saveMemoryNote(MemoryNoteCommand command) {
        if (command == null || command.content() == null || command.content().isBlank()) {
            throw new IllegalArgumentException("memory note content is required");
        }
        MemoryNoteEntity entity = command.id() == null
                ? new MemoryNoteEntity()
                : memoryNoteRepository.findById(command.id()).orElseGet(MemoryNoteEntity::new);
        entity.setAgentId(defaultIfBlank(command.agentId(),
                entity.getAgentId() == null ? "dev-agent" : entity.getAgentId()));
        entity.setSessionId(command.sessionId());
        // runId 是创建时固化的属性，不由管理员修改
        if (entity.getId() == null && entity.getRunId() == null) {
            entity.setRunId(null);
        }
        // source 默认 'manual' 表示管理员手工录入的长期记忆
        entity.setSource(defaultIfBlank(command.source(), "manual"));
        entity.setScope(normalizeScope(command.scope()));
        entity.setContent(command.content().trim());
        applyScopeOnNew(entity);
        MemoryNoteEntity saved = memoryNoteRepository.save(entity);
        return toMemoryNoteView(saved);
    }

    @Override
    @Transactional
    public void deleteMemoryNote(Long id) {
        if (id == null) return;
        memoryNoteRepository.deleteById(id);
    }

    private MemoryNoteView toMemoryNoteView(MemoryNoteEntity entity) {
        return new MemoryNoteView(
                entity.getId(),
                entity.getAgentId(),
                entity.getSessionId(),
                entity.getRunId(),
                entity.getScope(),
                entity.getSource(),
                entity.getContent(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    // ── Agent definitions (Plan A Phase 2) ────────────────────────────────

    @Override
    public List<AgentDefinitionView> listAgentDefinitions() {
        // P3:按 visibility 过滤 agent —— SYSTEM 全可见 / TENANT 匹配租户 / USER 匹配 owner
        // 或通过 user_agent_binding 被收藏。匿名期放行全部,行为等同 P1/P2。
        AuthPrincipal principal = SecurityContextHolder.current();
        java.util.Set<String> pinnedAgentIds = principal.anonymous()
                ? java.util.Set.of()
                : userAgentBindingRepository.findByUserId(principal.userId()).stream()
                        .map(UserAgentBindingEntity::getAgentId)
                        .collect(java.util.stream.Collectors.toSet());
        return agentDefinitionRepository.findAllByOrderByDisplayNameAsc().stream()
                .filter(e -> principal.anonymous()
                        || ResourceScopeFilter.matches(
                                e.getVisibility(), e.getScopeTenantId(), e.getScopeUserId(), principal)
                        || pinnedAgentIds.contains(e.getAgentId()))
                .map(this::toAgentDefinitionView)
                .toList();
    }

    @Override
    @Transactional
    public AgentDefinitionView saveAgentDefinition(AgentDefinitionCommand command) {
        if (command == null || command.agentId() == null || command.agentId().isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        String agentId = command.agentId().trim();
        // agentId 作为主键不可变；update 时用 findById 保留 createdAt
        AgentDefinitionEntity entity = agentDefinitionRepository.findById(agentId)
                .orElseGet(() -> {
                    AgentDefinitionEntity e = new AgentDefinitionEntity();
                    e.setAgentId(agentId);
                    return e;
                });
        entity.setDisplayName(defaultIfBlank(command.displayName(), agentId));
        entity.setDescription(command.description());
        entity.setSystemPrompt(command.systemPrompt() == null ? "" : command.systemPrompt());
        entity.setAgentMarkdown(command.agentMarkdown() == null ? "" : command.agentMarkdown());
        entity.setMemoryMarkdown(command.memoryMarkdown() == null ? "" : command.memoryMarkdown());
        entity.setEnabled(command.enabled() == null ? true : command.enabled());
        applyScopeOnNew(entity);
        return toAgentDefinitionView(agentDefinitionRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteAgentDefinition(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        agentDefinitionRepository.deleteById(agentId);
    }

    private AgentDefinitionView toAgentDefinitionView(AgentDefinitionEntity entity) {
        return new AgentDefinitionView(
                entity.getAgentId(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getSystemPrompt(),
                entity.getAgentMarkdown(),
                entity.getMemoryMarkdown(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "agent"; // admin-created notes default to agent scope (long-term, cross-session)
        }
        String trimmed = scope.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (trimmed) {
            case "session", "agent", "global" -> trimmed;
            default -> throw new IllegalArgumentException("invalid scope: " + scope
                    + " (expected session|agent|global)");
        };
    }
}
