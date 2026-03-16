package com.janyee.agent.runtime.skill;

import java.util.List;

public interface SkillDefinitionService {
    List<SkillPrompt> listEnabledSkillPrompts(String agentId);
}
