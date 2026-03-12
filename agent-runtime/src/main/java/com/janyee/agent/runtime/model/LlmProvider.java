package com.janyee.agent.runtime.model;

import reactor.core.publisher.Flux;

public interface LlmProvider {
    Flux<LlmStreamEvent> chatStream(LlmChatRequest request);
}
