package com.janyee.agent.infra.skill;

import com.janyee.agent.infra.prompt.BuiltinDocumentWorkflowCatalog;
import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.runtime.skill.SkillDefinitionService;
import com.janyee.agent.runtime.skill.SkillPrompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service
public class DatabaseSkillDefinitionService implements SkillDefinitionService {

    private final SkillDefinitionRepository repository;

    public DatabaseSkillDefinitionService(SkillDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SkillPrompt> listEnabledSkillPrompts(String agentId) {
        return Stream.concat(
                        BuiltinDocumentWorkflowCatalog.skillPrompts().stream(),
                        repository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                                .map(entity -> new SkillPrompt(
                                        entity.getSkillName(),
                                        entity.getDescription(),
                                        entity.getPromptTemplate()
                                ))
                )
                .toList();
    }
}
