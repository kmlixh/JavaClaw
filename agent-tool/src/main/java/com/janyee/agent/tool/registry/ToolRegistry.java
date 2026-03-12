package com.janyee.agent.tool.registry;

import com.janyee.agent.tool.AgentTool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {
    Optional<AgentTool> find(String toolName);

    List<AgentTool> listAll();
}
