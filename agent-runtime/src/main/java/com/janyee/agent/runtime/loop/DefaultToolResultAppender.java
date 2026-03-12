package com.janyee.agent.runtime.loop;

import org.springframework.stereotype.Component;

@Component
public class DefaultToolResultAppender implements ToolResultAppender {
    @Override
    public void append(ToolLoopContext context, ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            context.setLastModelRawOutput("Tool " + outcome.request().toolName() + " failed: " + outcome.errorMessage());
            return;
        }

        context.setLastModelRawOutput("""
                Tool result:
                name: %s
                success: %s
                summary: %s
                """.formatted(
                outcome.request().toolName(),
                outcome.success(),
                outcome.toolResult().summary()
        ));
    }
}
