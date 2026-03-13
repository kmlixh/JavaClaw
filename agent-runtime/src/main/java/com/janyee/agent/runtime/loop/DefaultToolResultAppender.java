package com.janyee.agent.runtime.loop;

import org.springframework.stereotype.Component;

@Component
public class DefaultToolResultAppender implements ToolResultAppender {
    @Override
    public void append(ToolLoopContext context, ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            context.setLastModelRawOutput("""
                    Tool execution failed.
                    Tool: %s
                    Arguments: %s
                    Error: %s

                    If the failure is recoverable, explain it briefly and ask for a narrower request.
                    Do not repeat the same tool call with identical arguments.
                    """.formatted(
                    outcome.request().toolName(),
                    safe(outcome.request().argumentsJson()),
                    safe(outcome.errorMessage())
            ));
            return;
        }

        context.setLastModelRawOutput("""
                Tool execution result:
                Tool: %s
                Arguments: %s
                Success: %s
                Summary: %s
                Data JSON:
                %s
                Artifacts JSON:
                %s

                Use the tool result above to answer the user's latest request directly.
                If the tool output already contains the needed information, provide the final answer instead of calling the same tool again.
                When listing files, include concrete file or directory names from Data JSON.
                """.formatted(
                outcome.request().toolName(),
                safe(outcome.request().argumentsJson()),
                outcome.success(),
                safe(outcome.toolResult().summary()),
                truncate(safe(outcome.toolResult().dataJson()), 4000),
                truncate(safe(outcome.toolResult().artifactsJson()), 2000)
        ));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }
}
