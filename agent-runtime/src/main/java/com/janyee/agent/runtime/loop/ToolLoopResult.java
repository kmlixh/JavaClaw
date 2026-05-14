package com.janyee.agent.runtime.loop;

import java.util.List;

public record ToolLoopResult(
        boolean success,
        ToolLoopState finalState,
        String finalAssistantText,
        List<ToolCallOutcome> toolOutcomes,
        List<ToolLoopIteration> iterations,
        String errorMessage,
        // 整个 run 累计的 token 用量(所有轮 LLM 调用之和)。供应商一直没返 usage 时全 null;
        // 部分轮返就累加部分(SimpleAgentRunner 写 session_message 时 null 直接落 NULL)。
        Integer totalPromptTokens,
        Integer totalCompletionTokens,
        Integer totalTokens
) {
    /** Back-compat 构造器:不带 token 累计的旧代码继续能编(取消 / 失败等不需要 token 路径)。 */
    public ToolLoopResult(
            boolean success,
            ToolLoopState finalState,
            String finalAssistantText,
            List<ToolCallOutcome> toolOutcomes,
            List<ToolLoopIteration> iterations,
            String errorMessage
    ) {
        this(success, finalState, finalAssistantText, toolOutcomes, iterations, errorMessage,
                null, null, null);
    }
}
