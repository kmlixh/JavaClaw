package com.janyee.agent.runtime.admin;

import java.util.List;

public interface AdminCatalogService {
    java.util.List<LlmConfigView> listLlmConfigs();

    LlmConfigView saveLlmConfig(LlmConfigCommand command);

    void deleteLlmConfig(String id);

    List<KnowledgeEntryView> listKnowledgeEntries(String agentId);

    KnowledgeEntryView saveKnowledgeEntry(KnowledgeEntryCommand command);

    void deleteKnowledgeEntry(String id);

    List<ToolDefinitionView> listToolDefinitions(String agentId);

    ToolDefinitionView saveToolDefinition(ToolDefinitionCommand command);

    void deleteToolDefinition(String id);

    List<SkillDefinitionView> listSkillDefinitions(String agentId);

    SkillDefinitionView saveSkillDefinition(SkillDefinitionCommand command);

    void deleteSkillDefinition(String id);

    List<DatasourceView> listDatasources();

    DatasourceView saveDatasource(DatasourceCommand command);

    void deleteDatasource(String id);

    /**
     * List memory notes for an agent, newest first. Returns session+agent+global scopes
     * (up to 40) so the admin UI can distinguish "one-shot memories" from "pinned long-term".
     */
    List<MemoryNoteView> listMemoryNotes(String agentId);

    /**
     * Create or update a memory note. Used by the admin UI to pin/unpin (change scope),
     * edit content, or create manual long-term notes.
     */
    MemoryNoteView saveMemoryNote(MemoryNoteCommand command);

    void deleteMemoryNote(Long id);

    // ── Agents (Plan A Phase 2) ───────────────────────────────────────────

    List<AgentDefinitionView> listAgentDefinitions();

    AgentDefinitionView saveAgentDefinition(AgentDefinitionCommand command);

    void deleteAgentDefinition(String agentId);
}
