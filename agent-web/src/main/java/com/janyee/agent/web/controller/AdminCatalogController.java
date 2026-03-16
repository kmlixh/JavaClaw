package com.janyee.agent.web.controller;

import com.janyee.agent.api.KnowledgeEntryRequest;
import com.janyee.agent.api.KnowledgeEntryResponse;
import com.janyee.agent.api.SkillDefinitionRequest;
import com.janyee.agent.api.SkillDefinitionResponse;
import com.janyee.agent.api.ToolDefinitionRequest;
import com.janyee.agent.api.ToolDefinitionResponse;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.KnowledgeEntryCommand;
import com.janyee.agent.runtime.admin.KnowledgeEntryView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import com.janyee.agent.runtime.admin.ToolDefinitionCommand;
import com.janyee.agent.runtime.admin.ToolDefinitionView;
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

    @GetMapping("/knowledge")
    public List<KnowledgeEntryResponse> listKnowledge(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listKnowledgeEntries(agentId).stream().map(this::toKnowledgeResponse).toList();
    }

    @PostMapping("/knowledge")
    public KnowledgeEntryResponse saveKnowledge(@RequestBody KnowledgeEntryRequest request) {
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
        adminCatalogService.deleteKnowledgeEntry(id);
    }

    @GetMapping("/tools")
    public List<ToolDefinitionResponse> listTools(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listToolDefinitions(agentId).stream().map(this::toToolResponse).toList();
    }

    @PostMapping("/tools")
    public ToolDefinitionResponse saveTool(@RequestBody ToolDefinitionRequest request) {
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
        adminCatalogService.deleteToolDefinition(id);
    }

    @GetMapping("/skills")
    public List<SkillDefinitionResponse> listSkills(@RequestParam("agentId") String agentId) {
        return adminCatalogService.listSkillDefinitions(agentId).stream().map(this::toSkillResponse).toList();
    }

    @PostMapping("/skills")
    public SkillDefinitionResponse saveSkill(@RequestBody SkillDefinitionRequest request) {
        return toSkillResponse(adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                request.id(),
                request.agentId(),
                request.skillName(),
                request.description(),
                request.promptTemplate(),
                request.configJson(),
                request.enabled()
        )));
    }

    @DeleteMapping("/skills/{id}")
    public void deleteSkill(@PathVariable("id") String id) {
        adminCatalogService.deleteSkillDefinition(id);
    }

    private KnowledgeEntryResponse toKnowledgeResponse(KnowledgeEntryView view) {
        return new KnowledgeEntryResponse(
                view.id(), view.agentId(), view.title(), view.content(), view.contentType(), view.source(),
                view.tagsJson(), view.enabled(), view.version(), view.createdAt(), view.updatedAt()
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
                view.id(), view.agentId(), view.skillName(), view.description(), view.promptTemplate(),
                view.configJson(), view.enabled(), view.version(), view.createdAt(), view.updatedAt()
        );
    }
}
