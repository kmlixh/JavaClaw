package com.janyee.agent.runtime.loop;

import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultModelTurnExecutor implements ModelTurnExecutor {

    private final LlmProvider llmProvider;

    public DefaultModelTurnExecutor(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public ModelTurnResult executeTurn(ToolLoopContext context) {
        List<LlmStreamEvent> events = llmProvider.chatStream(new LlmChatRequest(null, context.currentPrompt()))
                .collectList()
                .blockOptional()
                .orElse(List.of());

        String fullText = events.stream()
                .filter(event -> "token".equalsIgnoreCase(event.type()))
                .map(LlmStreamEvent::content)
                .reduce("", String::concat);

        return new ModelTurnResult(fullText, events, true, "stop");
    }
}
