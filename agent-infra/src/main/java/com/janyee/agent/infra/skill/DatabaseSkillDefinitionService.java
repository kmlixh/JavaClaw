package com.janyee.agent.infra.skill;

import com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository;
import com.janyee.agent.runtime.skill.SkillDefinitionService;
import com.janyee.agent.runtime.skill.SkillPrompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatabaseSkillDefinitionService implements SkillDefinitionService {

    private final SkillDefinitionRepository repository;

    public DatabaseSkillDefinitionService(
            SkillDefinitionRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public List<SkillPrompt> listEnabledSkillPrompts(String agentId) {
        return repository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .map(entity -> new SkillPrompt(
                        entity.getSkillName(),
                        entity.getDescription(),
                        entity.getPromptTemplate()
                ))
                .toList();
    }
}
