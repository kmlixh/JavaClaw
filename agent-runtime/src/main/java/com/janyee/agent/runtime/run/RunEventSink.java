package com.janyee.agent.runtime.run;

/**
 * 跨模块边界用的事件采集接口:agent-runtime 不能依赖 agent-infra,所以
 * {@code DefaultModelTurnExecutor} 等 runtime 组件通过这个接口把"LLM 输入 / 输出"
 * 这类内部事件交给 infra 层的 RunEventCollector 累积。
 *
 * <p>实现见 {@code com.janyee.agent.infra.runtime.RunEventCollector}。</p>
 */
public interface RunEventSink {

    /** 一次 LLM 调用前记录完整 prompt。 */
    void recordPromptSent(String runId, int iteration, String llmConfigId, String llmModel, String prompt);

    /**
     * 一次 LLM 调用结束记录响应。
     * @param promptTokens / completionTokens / totalTokens 供应商不返时传 null
     */
    void recordLlmResponse(String runId, int iteration, String finishReason, String fullText,
                           String thinking, Integer promptTokens, Integer completionTokens, Integer totalTokens);

    /**
     * 一次 LLM 调用失败/重试时记录原因。errorType 类似 ReadTimeoutException;errorMessage 简短描述。
     */
    void recordLlmAttemptFailed(String runId, int iteration, int attempt, String errorType, String errorMessage);
}
