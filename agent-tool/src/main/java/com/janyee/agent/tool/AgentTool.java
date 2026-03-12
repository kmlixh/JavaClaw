package com.janyee.agent.tool;

import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;

public interface AgentTool {
    String name();

    ToolSchema schema();

    ToolResult execute(ToolInvocation invocation);
}
