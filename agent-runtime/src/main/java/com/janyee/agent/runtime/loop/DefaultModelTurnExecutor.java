package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import com.janyee.agent.tool.policy.ToolPolicyService;
import com.janyee.agent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultModelTurnExecutor implements ModelTurnExecutor {

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ToolPolicyService toolPolicyService;

    public DefaultModelTurnExecutor(LlmProvider llmProvider, ToolRegistry toolRegistry, ToolPolicyService toolPolicyService) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.toolPolicyService = toolPolicyService;
    }

    @Override
    public ModelTurnResult executeTurn(ToolLoopContext context) {
        List<ToolSchema> toolSchemas = toolRegistry.listAll().stream()
                .filter(tool -> toolPolicyService.isAllowed(context.agentId(), tool.name()))
                .map(tool -> tool.schema())
                .toList();
        List<LlmStreamEvent> events = llmProvider.chatStream(new LlmChatRequest(null, context.currentPrompt(), toolSchemas))
                .collectList()
                .blockOptional()
                .orElse(List.of());

        String fullText = events.stream()
                .filter(event -> "token".equalsIgnoreCase(event.type()))
                .map(LlmStreamEvent::content)
                .reduce("", String::concat);
        String finishReason = events.stream()
                .filter(event -> "finish".equalsIgnoreCase(event.type()))
                .map(LlmStreamEvent::content)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse("stop");
        boolean finished = events.stream()
                .anyMatch(event -> "finish".equalsIgnoreCase(event.type()));

        return new ModelTurnResult(fullText, events, finished, finishReason);
    }
}
