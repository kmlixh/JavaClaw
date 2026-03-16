package com.janyee.agent.runtime.admin;

import java.util.List;

public interface AdminCatalogService {
    List<KnowledgeEntryView> listKnowledgeEntries(String agentId);

    KnowledgeEntryView saveKnowledgeEntry(KnowledgeEntryCommand command);

    void deleteKnowledgeEntry(String id);

    List<ToolDefinitionView> listToolDefinitions(String agentId);

    ToolDefinitionView saveToolDefinition(ToolDefinitionCommand command);

    void deleteToolDefinition(String id);

    List<SkillDefinitionView> listSkillDefinitions(String agentId);

    SkillDefinitionView saveSkillDefinition(SkillDefinitionCommand command);

    void deleteSkillDefinition(String id);
}
