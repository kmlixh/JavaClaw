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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)\"(password|apiKey|api_key|token|accessToken|access_token)\"\\s*:\\s*\"([^\"]*)\""
    );
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)(password|apiKey|api_key|token|accessToken|access_token)\\s*=\\s*([^,\\s\\]}]+)"
    );

    private final ToolRegistry toolRegistry;

    public DefaultToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolCallOutcome execute(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision) {
        AgentTool tool = toolRegistry.find(decision.normalizedToolName())
                .orElseThrow(() -> new ToolExecutionFailedException("tool not found: " + decision.normalizedToolName(), null));

        long start = System.currentTimeMillis();
        log.info("tool.execute.start runId={}, toolName={}, arguments={}",
                context.runId(), decision.normalizedToolName(), redactSecrets(decision.normalizedArgumentsJson()));
        try {
            ToolResult toolResult = tool.execute(new ToolInvocation(
                    context.agentId(),
                    context.runId(),
                    context.sessionId(),
                    context.userId(),
                    decision.normalizedToolName(),
                    decision.normalizedArgumentsJson()
            ));
            ToolCallOutcome outcome = new ToolCallOutcome(
                    request,
                    decision,
                    true,
                    toolResult.ok(),
                    toolResult,
                    toolResult.error(),
                    System.currentTimeMillis() - start
            );
            log.info("tool.execute.finish runId={}, toolName={}, ok={}, durationMs={}, error={}, summary={}",
                    context.runId(),
                    decision.normalizedToolName(),
                    toolResult.ok(),
                    outcome.durationMillis(),
                    toolResult.error(),
                    safe(toolResult.summary()));
            return outcome;
        } catch (Exception error) {
            log.error("tool.execute.error runId={}, toolName={}", context.runId(), decision.normalizedToolName(), error);
            throw new ToolExecutionFailedException("tool execution failed: " + decision.normalizedToolName(), error);
        }
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    private String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JSON_SECRET_PATTERN.matcher(value)
                .replaceAll("\"$1\":\"***\"");
        return KV_SECRET_PATTERN.matcher(redacted)
                .replaceAll("$1=***");
    }
}
