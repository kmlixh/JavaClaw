package com.janyee.agent.runtime.agent;

import com.janyee.agent.domain.AgentDefinition;

import java.util.List;

public interface AgentDefinitionService {
    AgentDefinition getAgent(String agentId);

    List<AgentDefinition> listAgents();
}
