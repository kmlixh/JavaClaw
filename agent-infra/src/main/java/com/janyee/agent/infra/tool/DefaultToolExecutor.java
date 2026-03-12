package com.janyee.agent.infra.tool;

import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.runtime.loop.ToolCallDecision;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolCallRequest;
import com.janyee.agent.runtime.loop.ToolExecutionFailedException;
import com.janyee.agent.runtime.loop.ToolExecutor;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.tool.AgentTool;
import com.janyee.agent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Component;

@Component
public class DefaultToolExecutor implements ToolExecutor {

    private final ToolRegistry toolRegistry;

    public DefaultToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolCallOutcome execute(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision) {
        AgentTool tool = toolRegistry.find(decision.normalizedToolName())
                .orElseThrow(() -> new ToolExecutionFailedException("tool not found: " + decision.normalizedToolName(), null));

        long start = System.currentTimeMillis();
        try {
            ToolResult toolResult = tool.execute(new ToolInvocation(
                    context.agentId(),
                    context.runId(),
                    context.sessionId(),
                    context.userId(),
                    decision.normalizedToolName(),
                    decision.normalizedArgumentsJson()
            ));
            return new ToolCallOutcome(
                    request,
                    decision,
                    true,
                    toolResult.ok(),
                    toolResult,
                    toolResult.error(),
                    System.currentTimeMillis() - start
            );
        } catch (Exception error) {
            throw new ToolExecutionFailedException("tool execution failed: " + decision.normalizedToolName(), error);
        }
    }
}
