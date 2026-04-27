package com.janyee.agent.web.controller;

import com.janyee.agent.api.AgentDefinitionAdminRequest;
import com.janyee.agent.api.AgentDefinitionAdminResponse;
import com.janyee.agent.api.DatasourceAdminRequest;
import com.janyee.agent.api.DatasourceAdminResponse;
import com.janyee.agent.api.KnowledgeEntryRequest;
import com.janyee.agent.api.KnowledgeEntryResponse;
import com.janyee.agent.api.LlmConfigAdminRequest;
import com.janyee.agent.api.LlmConfigAdminResponse;
import com.janyee.agent.api.MemoryNoteAdminRequest;
import com.janyee.agent.api.MemoryNoteAdminResponse;
import com.janyee.agent.api.SkillDefinitionRequest;
import com.janyee.agent.api.SkillDefinitionResponse;
import com.janyee.agent.api.ToolDefinitionRequest;
import com.janyee.agent.api.ToolDefinitionResponse;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.AgentDefinitionCommand;
import com.janyee.agent.runtime.admin.AgentDefinitionView;
import com.janyee.agent.runtime.admin.DatasourceCommand;
import com.janyee.agent.runtime.admin.DatasourceView;
import com.janyee.agent.runtime.admin.KnowledgeEntryCommand;
import com.janyee.agent.runtime.admin.KnowledgeEntryView;
import com.janyee.agent.runtime.admin.LlmConfigCommand;
import com.janyee.agent.runtime.admin.LlmConfigView;
import com.janyee.agent.runtime.admin.MemoryNoteCommand;
import com.janyee.agent.runtime.admin.MemoryNoteView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import com.janyee.agent.runtime.admin.ToolDefinitionCommand;
import com.janyee.agent.runtime.admin.ToolDefinitionView;
import com.janyee.agent.infra.auth.PermissionGate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;

    public AdminCatalogController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @GetMapping("/llms")
    public List<LlmConfigAdminResponse> listLlms() {
        return adminCatalogService.listLlmConfigs().stream().map(this::toLlmResponse).toList();
    }

    @PostMapping("/llms")
    public LlmConfigAdminResponse saveLlm(@RequestBody LlmConfigAdminRequest request) {
        PermissionGate.require("llm.manage");
        return toLlmResponse(adminCatalogService.saveLlmConfig(new LlmConfigCommand(
                request.id(),
                request.provider(),
                request.displayName(),
                request.model(),
                request.modelMappingJson(),
                request.baseUrl(),
                request.apiKey(),
                request.chatPath(),
                request.stream(),
                request.enabled(),
                request.defaultConfig()
        )));
    }

    @DeleteMapping("/llms/{id}")
    public void deleteLlm(@PathVariable("id") String id) {
        PermissionGate.require("llm.manage");
        adminCatalogService.deleteLlmConfig(id);
    }

    @GetMapping("/knowledge")
    public List<KnowledgeEntryResponse> listKnowledge(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listKnowledgeEntries(agentId).stream().map(this::toKnowledgeResponse).toList();
    }

    @PostMapping("/knowledge")
    public KnowledgeEntryResponse saveKnowledge(@RequestBody KnowledgeEntryRequest request) {
        PermissionGate.require("knowledge.manage");
        return toKnowledgeResponse(adminCatalogService.saveKnowledgeEntry(new KnowledgeEntryCommand(
                request.id(),
                request.agentId(),
                request.title(),
                request.content(),
                request.contentType(),
                request.source(),
                request.tagsJson(),
                request.enabled()
        )));
    }

    @DeleteMapping("/knowledge/{id}")
    public void deleteKnowledge(@PathVariable("id") String id) {
        PermissionGate.require("knowledge.manage");
        adminCatalogService.deleteKnowledgeEntry(id);
    }

    @GetMapping("/tools")
    public List<ToolDefinitionResponse> listTools(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listToolDefinitions(agentId).stream().map(this::toToolResponse).toList();
    }

    @PostMapping("/tools")
    public ToolDefinitionResponse saveTool(@RequestBody ToolDefinitionRequest request) {
        PermissionGate.require("agent.edit");
        return toToolResponse(adminCatalogService.saveToolDefinition(new ToolDefinitionCommand(
                request.id(),
                request.agentId(),
                request.toolName(),
                request.displayName(),
                request.description(),
                request.schemaJson(),
                request.toolType(),
                request.configJson(),
                request.enabled(),
                request.approvalRequired()
        )));
    }

    @DeleteMapping("/tools/{id}")
    public void deleteTool(@PathVariable("id") String id) {
        PermissionGate.require("agent.edit");
        adminCatalogService.deleteToolDefinition(id);
    }

    @GetMapping("/skills")
    public List<SkillDefinitionResponse> listSkills(
            @RequestParam(value = "agentId", required = false) String agentId
    ) {
        return adminCatalogService.listSkillDefinitions(agentId).stream().map(this::toSkillResponse).toList();
    }

    @PostMapping("/skills")
    public SkillDefinitionResponse saveSkill(@RequestBody SkillDefinitionRequest request) {
        PermissionGate.require("skill.manage");
        return toSkillResponse(adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                request.id(),
                request.agentId(),
                request.agentIds(),
                request.skillName(),
                request.description(),
                request.promptTemplate(),
                request.configJson(),
                request.triggerKeywords(),
                request.enabled()
        )));
    }

    @DeleteMapping("/skills/{id}")
    public void deleteSkill(@PathVariable("id") String id) {
        PermissionGate.require("skill.manage");
        adminCatalogService.deleteSkillDefinition(id);
    }

    @GetMapping("/datasources")
    public List<DatasourceAdminResponse> listDatasources() {
        return adminCatalogService.listDatasources().stream().map(this::toDatasourceResponse).toList();
    }

    @PostMapping("/datasources")
    public DatasourceAdminResponse saveDatasource(@RequestBody DatasourceAdminRequest request) {
        PermissionGate.require("datasource.manage");
        return toDatasourceResponse(adminCatalogService.saveDatasource(new DatasourceCommand(
                request.id(),
                request.displayName(),
                request.jdbcUrl(),
                request.username(),
                request.password(),
                request.dialect(),
                request.description(),
                request.enabled()
        )));
    }

    @DeleteMapping("/datasources/{id}")
    public void deleteDatasource(@PathVariable("id") String id) {
        PermissionGate.require("datasource.manage");
        adminCatalogService.deleteDatasource(id);
    }

    // ── Memory notes (Plan A Phase 1) ─────────────────────────────────────

    @GetMapping("/memory-notes")
    public List<MemoryNoteAdminResponse> listMemoryNotes(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listMemoryNotes(agentId).stream().map(this::toMemoryNoteResponse).toList();
    }

    @PostMapping("/memory-notes")
    public MemoryNoteAdminResponse saveMemoryNote(@RequestBody MemoryNoteAdminRequest request) {
        PermissionGate.require("memory.manage");
        return toMemoryNoteResponse(adminCatalogService.saveMemoryNote(new MemoryNoteCommand(
                request.id(),
                request.agentId(),
                request.sessionId(),
                request.scope(),
                request.source(),
                request.content()
        )));
    }

    @DeleteMapping("/memory-notes/{id}")
    public void deleteMemoryNote(@PathVariable("id") Long id) {
        PermissionGate.require("memory.manage");
        adminCatalogService.deleteMemoryNote(id);
    }

    // ── Agent definitions (Plan A Phase 2) ─────────────────────────────────

    @GetMapping("/agents")
    public List<AgentDefinitionAdminResponse> listAgentDefinitions() {
        return adminCatalogService.listAgentDefinitions().stream().map(this::toAgentResponse).toList();
    }

    @PostMapping("/agents")
    public AgentDefinitionAdminResponse saveAgentDefinition(@RequestBody AgentDefinitionAdminRequest request) {
        PermissionGate.require("agent.edit");
        return toAgentResponse(adminCatalogService.saveAgentDefinition(new AgentDefinitionCommand(
                request.agentId(),
                request.displayName(),
                request.description(),
                request.systemPrompt(),
                request.agentMarkdown(),
                request.memoryMarkdown(),
                request.enabled()
        )));
    }

    @DeleteMapping("/agents/{agentId}")
    public void deleteAgentDefinition(@PathVariable("agentId") String agentId) {
        PermissionGate.require("agent.edit");
        adminCatalogService.deleteAgentDefinition(agentId);
    }

    private AgentDefinitionAdminResponse toAgentResponse(AgentDefinitionView view) {
        return new AgentDefinitionAdminResponse(
                view.agentId(), view.displayName(), view.description(),
                view.systemPrompt(), view.agentMarkdown(), view.memoryMarkdown(),
                view.enabled(), view.createdAt(), view.updatedAt()
        );
    }

    private MemoryNoteAdminResponse toMemoryNoteResponse(MemoryNoteView view) {
        return new MemoryNoteAdminResponse(
                view.id(), view.agentId(), view.sessionId(), view.runId(),
                view.scope(), view.source(), view.content(),
                view.createdAt(), view.updatedAt()
        );
    }

    private KnowledgeEntryResponse toKnowledgeResponse(KnowledgeEntryView view) {
        return new KnowledgeEntryResponse(
                view.id(), view.agentId(), view.title(), view.content(), view.contentType(), view.source(),
                view.tagsJson(), view.enabled(), view.version(), view.createdAt(), view.updatedAt()
        );
    }

    private LlmConfigAdminResponse toLlmResponse(LlmConfigView view) {
        return new LlmConfigAdminResponse(
                view.id(), view.provider(), view.displayName(), view.model(), view.modelMappingJson(),
                view.baseUrl(), view.apiKey(),
                view.chatPath(), view.stream(), view.enabled(), view.defaultConfig(), view.createdAt(), view.updatedAt()
        );
    }

    private ToolDefinitionResponse toToolResponse(ToolDefinitionView view) {
        return new ToolDefinitionResponse(
                view.id(), view.agentId(), view.toolName(), view.displayName(), view.description(), view.schemaJson(),
                view.toolType(), view.configJson(), view.enabled(), view.approvalRequired(), view.version(),
                view.createdAt(), view.updatedAt()
        );
    }

    private SkillDefinitionResponse toSkillResponse(SkillDefinitionView view) {
        return new SkillDefinitionResponse(
                view.id(), view.agentId(), view.agentIds(), view.skillName(), view.description(),
                view.promptTemplate(), view.configJson(), view.triggerKeywords(),
                view.enabled(), view.version(), view.createdAt(), view.updatedAt()
        );
    }

    private DatasourceAdminResponse toDatasourceResponse(DatasourceView view) {
        return new DatasourceAdminResponse(
                view.id(), view.displayName(), view.jdbcUrl(), view.username(), view.passwordSet(),
                view.dialect(), view.description(), view.enabled(), view.createdAt(), view.updatedAt()
        );
    }
}
