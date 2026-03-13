package com.janyee.agent.runtime.model;

import com.janyee.agent.domain.ToolSchema;

import java.util.List;

public record LlmChatRequest(
        String model,
        String configId,
        String prompt,
        List<ToolSchema> tools
) {
}
