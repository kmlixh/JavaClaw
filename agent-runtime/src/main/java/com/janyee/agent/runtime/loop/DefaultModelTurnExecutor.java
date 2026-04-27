package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import com.janyee.agent.tool.policy.ToolPolicyService;
import com.janyee.agent.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class DefaultModelTurnExecutor implements ModelTurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelTurnExecutor.class);
    private static final int MAX_MODEL_ATTEMPTS = 20;
    private static final long MODEL_OUTPUT_EMIT_INTERVAL_MILLIS = 700L;
    private static final int MODEL_OUTPUT_EMIT_DELTA_CHARS = 40;
    // 指数退避基数 + 天花板:第 N 次重试前等 min(BACKOFF_BASE * 2^(N-1), BACKOFF_CAP) ms 再加抖动。
    //   N=1 → 1s+,  N=2 → 2s+, ..., N=6 → 32s(被 cap 成 30s),之后一直 30s+抖动。
    // 没有 cap 的话 20 次重试会等 ~6 天,显然不合理。加 cap 后最多 20 次总退避 ~(1+2+4+8+16+30*15)=481s ~= 8min,
    // 加上每次 HTTP 实际耗时,整个 loop 最坏情况 ~20-30 分钟。
    // 抖动防止并发请求同时重试打爆上游。
    private static final long BACKOFF_BASE_MILLIS = 1000L;
    private static final long BACKOFF_CAP_MILLIS = 30_000L;
    private static final long BACKOFF_JITTER_MILLIS = 500L;

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ToolPolicyService toolPolicyService;

    public DefaultModelTurnExecutor(LlmProvider llmProvider, ToolRegistry toolRegistry, ToolPolicyService toolPolicyService) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.toolPolicyService = toolPolicyService;
    }

    @Override
    public ModelTurnResult executeTurn(ToolLoopContext context) {
        List<ToolSchema> toolSchemas = toolRegistry.listAll().stream()
                .filter(tool -> toolPolicyService.isAllowed(context.agentId(), tool.name()))
                .map(tool -> tool.schema())
                .toList();
        List<LlmStreamEvent> events = executeWithRetry(context, toolSchemas);

        events.stream()
                .filter(event -> "error".equalsIgnoreCase(event.type()))
                .findFirst()
                .ifPresent(event -> {
                    throw new ToolLoopException(event.content());
                });

        String fullText = events.stream()
                .filter(event -> "token".equalsIgnoreCase(event.type()))
                .map(LlmStreamEvent::content)
                .reduce("", String::concat);
        String finishReason = events.stream()
                .filter(event -> "finish".equalsIgnoreCase(event.type()))
                .map(LlmStreamEvent::content)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse("stop");
        boolean finished = events.stream()
                .anyMatch(event -> "finish".equalsIgnoreCase(event.type()));

        return new ModelTurnResult(fullText, events, finished, finishReason);
    }

    private List<LlmStreamEvent> executeWithRetry(ToolLoopContext context, List<ToolSchema> toolSchemas) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_MODEL_ATTEMPTS; attempt++) {
            // 每次重试前先瞄一眼 cancel flag:用户点终止的那一刻如果正好落在两次重试之间,
            // 这里能立刻跳出,不用白白发起下一个 LLM HTTP 请求。
            if (context.isCancelRequested()) {
                log.info("tool.loop.model_attempt_cancel_detected runId={}, iteration={}, attempt={}",
                        context.runId(), context.iterationCount(), attempt);
                return List.of();
            }
            List<LlmStreamEvent> events = new ArrayList<>();
            StringBuilder visibleText = new StringBuilder();
            long[] lastEmitAt = {0L};
            int[] lastEmitLength = {0};
            try {
                // takeUntilOther(cancelSignal):用户按终止时,ToolLoopContext.requestCancel 会
                // 往 sink 推一个值,这里的 stream 立刻 complete,blockLast 立即返回,不用等
                // LLM 回完当前那一轮流式回复 —— 这才是用户期望的"点一下立刻停"。
                llmProvider.chatStream(new LlmChatRequest(context.llmModel(), context.llmConfigId(), context.currentPrompt(), toolSchemas))
                        .takeUntilOther(context.cancelSignal())
                        .doOnNext(event -> {
                            events.add(event);
                            if ("token".equalsIgnoreCase(event.type()) && event.content() != null && !event.content().isBlank()) {
                                visibleText.append(event.content());
                                emitModelOutputProgress(context, visibleText, lastEmitAt, lastEmitLength, false);
                            }
                        })
                        .blockLast();
                emitModelOutputProgress(context, visibleText, lastEmitAt, lastEmitLength, true);
                // Stream 被 cancel 信号掐断时也会走到这里(blockLast 正常返回),不要再把
                // 半截 events 往外传,让 orchestrator 在下一轮起始的 cancel 检查里优雅收尾。
                if (context.isCancelRequested()) {
                    log.info("tool.loop.model_stream_cancelled runId={}, iteration={}, attempt={}, events={}",
                            context.runId(), context.iterationCount(), attempt, events.size());
                    return List.of();
                }
                return events;
            } catch (RuntimeException error) {
                lastError = error;
                emitModelOutputProgress(context, visibleText, lastEmitAt, lastEmitLength, true);
                // 取消期间 reactor 可能抛 CancellationException,这种情况不重试,也别上报为错误。
                if (context.isCancelRequested()) {
                    log.info("tool.loop.model_stream_cancel_threw runId={}, attempt={}, causeType={}",
                            context.runId(), attempt, deepestCauseType(error));
                    return List.of();
                }
                String causeType = deepestCauseType(error);
                log.warn("tool.loop.model_attempt_failed runId={}, iteration={}, attempt={}/{}, causeType={}, message={}",
                        context.runId(), context.iterationCount(), attempt, MAX_MODEL_ATTEMPTS,
                        causeType, safeMessage(error));
                if (attempt >= MAX_MODEL_ATTEMPTS || !isRetryable(error)) {
                    throw error;
                }
                // 带上 resetText=1 是给前端一个明确信号:这次是断线重试,之前那截半截回复请扔掉,
                // 不要拼到新流后面。前端匹配 phase=MODEL_RETRY 就会清掉 liveAssistant.content。
                // 1L << (attempt - 1) 在 attempt>=64 时会溢出(理论不可能,但保险取 min(62, ...))。
                int shift = Math.min(attempt - 1, 30);
                long raw = BACKOFF_BASE_MILLIS * (1L << shift);
                long backoffMillis = Math.min(raw, BACKOFF_CAP_MILLIS)
                        + ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MILLIS);
                context.emitEvent(
                        AgentEventType.RUN_STATUS,
                        "step=%d phase=MODEL_RETRY resetText=1 模型响应超时或连接中断(%s),已丢弃半截输出,%dms 后重试第 %d/%d 次"
                                .formatted(context.iterationCount() + 1, causeType, backoffMillis,
                                        attempt + 1, MAX_MODEL_ATTEMPTS)
                );
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ToolLoopException("model retry interrupted", interrupted);
                }
            }
        }
        throw new ToolLoopException("model request failed", lastError);
    }

    private void emitModelOutputProgress(
            ToolLoopContext context,
            StringBuilder visibleText,
            long[] lastEmitAt,
            int[] lastEmitLength,
            boolean force
    ) {
        String text = visibleText == null ? "" : visibleText.toString().trim();
        if (text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        int length = text.length();
        if (!force
                && now - lastEmitAt[0] < MODEL_OUTPUT_EMIT_INTERVAL_MILLIS
                && length - lastEmitLength[0] < MODEL_OUTPUT_EMIT_DELTA_CHARS) {
            return;
        }
        lastEmitAt[0] = now;
        lastEmitLength[0] = length;
        context.emitEvent(
                AgentEventType.RUN_STATUS,
                "step=%d phase=MODEL_OUTPUT %s".formatted(context.iterationCount() + 1, truncate(text, 1200))
        );
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String type = current.getClass().getSimpleName();
            String message = current.getMessage();
            if ("ReadTimeoutException".equals(type)
                    || "WriteTimeoutException".equals(type)
                    || "TimeoutException".equals(type)
                    || "PrematureCloseException".equals(type)
                    || "ClosedChannelException".equals(type)
                    || (message != null && message.toLowerCase().contains("timeout"))
                    || (message != null && message.toLowerCase().contains("connection reset"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }

    private String deepestCauseType(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "Unknown" : current.getClass().getSimpleName();
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "(null)";
        String msg = error.getMessage();
        if (msg == null) return "(no message)";
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}
