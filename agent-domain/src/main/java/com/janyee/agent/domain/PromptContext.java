package com.janyee.agent.domain;

public record PromptContext(
        String systemPrompt,
        String assembledPrompt
) {
}
