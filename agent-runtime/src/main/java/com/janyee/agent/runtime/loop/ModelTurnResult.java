package com.janyee.agent.runtime.loop;

import com.janyee.agent.runtime.model.LlmStreamEvent;

import java.util.List;

public record ModelTurnResult(
        String fullText,
        List<LlmStreamEvent> rawEvents,
        boolean finish,
        String finishReason
) {
}
