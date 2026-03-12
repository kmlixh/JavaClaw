package com.janyee.agent.tool.builtin;

import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class EchoTool implements AgentTool {
    @Override
    public String name() {
        return "echo";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "echo",
                "Echo input arguments",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return new ToolResult(true, "echo ok", invocation.argumentsJson(), "[]", null);
    }
}
