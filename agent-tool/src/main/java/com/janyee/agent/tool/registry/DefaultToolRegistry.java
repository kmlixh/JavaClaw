package com.janyee.agent.tool.registry;

import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools;

    public DefaultToolRegistry(List<AgentTool> agentTools) {
        this.tools = agentTools.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AgentTool::name,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("duplicate tool name: " + left.name());
                        }
                ));
    }

    @Override
    public Optional<AgentTool> find(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    @Override
    public List<AgentTool> listAll() {
        return List.copyOf(tools.values());
    }
}
