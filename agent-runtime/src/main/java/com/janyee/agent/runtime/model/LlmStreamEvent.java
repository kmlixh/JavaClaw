package com.janyee.agent.runtime.model;

public record LlmStreamEvent(
        String type,
        String content
) {
}
