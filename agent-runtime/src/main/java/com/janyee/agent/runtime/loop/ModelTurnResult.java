package com.janyee.agent.runtime.loop;

import com.janyee.agent.runtime.model.LlmStreamEvent;

import java.util.List;

public record ModelTurnResult(
        String fullText,
        List<LlmStreamEvent> rawEvents,
        boolean finish,
        String finishReason,
        // 单次 LLM 调用的 token 用量。供应商不返 usage 时全部 null。
        // tool loop 多轮调用时,DefaultToolLoopOrchestrator 会把每轮的非 null 值累加到 ToolLoopResult。
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
