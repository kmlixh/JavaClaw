package com.janyee.agent.runtime.model;

public record LlmChatRequest(
        String model,
        String prompt
) {
}
