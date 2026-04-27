package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.skill.SkillGuard;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ToolLoopContext {

    private final String sessionId;
    private final String runId;
    private final String agentId;
    private final String userId;
    private final String llmConfigId;
    private final String llmModel;
    private final int maxIterations;
    private final PromptContext promptContext;
    private final StringBuilder assistantTextBuffer = new StringBuilder();
    private final List<ToolLoopIteration> iterations = new ArrayList<>();
    private final List<ToolCallOutcome> toolOutcomes = new ArrayList<>();
    private final Map<String, String> schemaContexts = new LinkedHashMap<>();
    private final RunPlan runPlan = new RunPlan();
    private final List<CompletedToolSummary> completedToolSummaries = new ArrayList<>();

    private ToolLoopState state = ToolLoopState.INITIALIZING;
    private int iterationCount;
    private String lastModelRawOutput;
    private String schemaContext;
    private ToolCallRequest pendingToolCall;
    private Consumer<AgentEvent> eventConsumer = event -> {};
    private SkillGuard guard = SkillGuard.NONE;
    private int runEndGateNudgeCount;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<String> cancelReason = new AtomicReference<>("");
    // Reactor sink that reactive pipelines (LLM chatStream) can subscribe to for takeUntilOther.
    // replay().limit(1) 的 serialization-friendly 版本:只有 onNext 或 onComplete 能把它关掉,
    // 后续订阅者仍能立刻收到信号;这正是 takeUntilOther 想要的 happened-before 语义。
    private final Sinks.One<Object> cancelSink = Sinks.one();

    public ToolLoopContext(RunRequest request, String runId, PromptContext promptContext, int maxIterations) {
        this.sessionId = request.sessionId();
        this.runId = runId;
        this.agentId = request.agentId();
        this.userId = request.userId();
        this.llmConfigId = request.llmConfigId();
        this.llmModel = request.llmModel();
        this.promptContext = promptContext;
        this.maxIterations = maxIterations;
    }

    public String sessionId() {
        return sessionId;
    }

    public String runId() {
        return runId;
    }

    public String agentId() {
        return agentId;
    }

    public String userId() {
        return userId;
    }

    public String llmConfigId() {
        return llmConfigId;
    }

    public String llmModel() {
        return llmModel;
    }

    public ToolLoopState state() {
        return state;
    }

    public int iterationCount() {
        return iterationCount;
    }

    public int maxIterations() {
        return maxIterations;
    }

    private static final String SCHEMA_USAGE_RULES = """
            [Schema Usage Rules]
            - SQL must use only columns listed above.
            - Do not invent aliases like usage_freq/usage_frequency/long/lat unless they appear in cols.
            - Prefer freq/lon/lat column names indicated by hint when present.
            """;

    private static final int MAX_COMPLETED_SUMMARIES_IN_PROMPT = 30;

    public String currentPrompt() {
        StringBuilder prompt = new StringBuilder(promptContext.assembledPrompt());
        if (schemaContext != null && !schemaContext.isBlank()) {
            prompt.append("\n\n[Schema Context]\n").append(schemaContext)
                    .append("\n\n").append(SCHEMA_USAGE_RULES);
        }
        if (!runPlan.isEmpty()) {
            prompt.append("\n\n[Plan]\n").append(runPlan.renderCompact());
        }
        if (!completedToolSummaries.isEmpty()) {
            prompt.append("\n\n[Completed Queries]\n").append(renderCompletedSummaries());
            prompt.append("\n(Do not re-issue a tool call that already appears above with the same semantic target.)");
        }
        if (lastModelRawOutput != null && !lastModelRawOutput.isBlank()) {
            prompt.append("\n\n").append(lastModelRawOutput);
        }
        return prompt.toString();
    }

    private String renderCompletedSummaries() {
        int total = completedToolSummaries.size();
        int start = Math.max(0, total - MAX_COMPLETED_SUMMARIES_IN_PROMPT);
        StringBuilder builder = new StringBuilder();
        if (start > 0) {
            builder.append("(earlier ").append(start).append(" omitted)\n");
        }
        for (int i = start; i < total; i++) {
            CompletedToolSummary s = completedToolSummaries.get(i);
            builder.append("- ").append(s.toolName())
                    .append(" | fp=").append(s.argumentsFingerprint())
                    .append(" | rows=").append(s.rowCount());
            if (s.stepId() != null && !s.stepId().isBlank()) {
                builder.append(" | step=").append(s.stepId());
            }
            if (s.summary() != null && !s.summary().isBlank()) {
                String summary = s.summary();
                if (summary.length() > 160) {
                    summary = summary.substring(0, 160) + "…";
                }
                builder.append(" | ").append(summary);
            }
            builder.append('\n');
        }
        return builder.toString().stripTrailing();
    }

    public String assistantText() {
        return assistantTextBuffer.toString();
    }

    public ToolCallRequest pendingToolCall() {
        return pendingToolCall;
    }

    public List<ToolLoopIteration> iterations() {
        return List.copyOf(iterations);
    }

    public List<ToolCallOutcome> toolOutcomes() {
        return List.copyOf(toolOutcomes);
    }

    public String lastModelRawOutput() {
        return lastModelRawOutput;
    }

    public String schemaContext() {
        return schemaContext;
    }

    public void advanceState(ToolLoopState nextState) {
        this.state = nextState;
    }

    public void incrementIteration() {
        this.iterationCount++;
        if (iterationCount > maxIterations) {
            throw new MaxToolIterationExceededException(iterationCount, maxIterations);
        }
    }

    public void setPendingToolCall(ToolCallRequest request) {
        this.pendingToolCall = request;
    }

    public void clearPendingToolCall() {
        this.pendingToolCall = null;
    }

    public void appendAssistantText(String delta) {
        this.assistantTextBuffer.append(delta);
    }

    public void recordIteration(ToolLoopIteration iteration) {
        this.iterations.add(iteration);
    }

    public void addToolOutcome(ToolCallOutcome outcome) {
        this.toolOutcomes.add(outcome);
    }

    public void setLastModelRawOutput(String lastModelRawOutput) {
        this.lastModelRawOutput = lastModelRawOutput;
    }

    public void setSchemaContext(String schemaContext) {
        this.schemaContext = schemaContext;
    }

    public boolean hasSchemaContext(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return schemaContexts.containsKey(key);
    }

    public void addSchemaContext(String key, String schemaContext) {
        if (key == null || key.isBlank() || schemaContext == null || schemaContext.isBlank()) {
            return;
        }
        schemaContexts.put(key, schemaContext);
        this.schemaContext = String.join("\n\n", schemaContexts.values());
    }

    public RunPlan runPlan() {
        return runPlan;
    }

    public List<CompletedToolSummary> completedToolSummaries() {
        return List.copyOf(completedToolSummaries);
    }

    /**
     * Record a successful tool execution so the LLM can see "already done" on later iterations.
     * If the plan has an IN_PROGRESS step, the summary is also attached to that step.
     */
    public void addCompletedToolSummary(CompletedToolSummary summary) {
        if (summary == null) {
            return;
        }
        completedToolSummaries.add(summary);
        runPlan.currentInProgressStep().ifPresent(step -> step.attachToolSummary(summary));
    }

    public void validateInvariant() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ToolLoopException("sessionId is required");
        }
        if (runId == null || runId.isBlank()) {
            throw new ToolLoopException("runId is required");
        }
        if (maxIterations <= 0) {
            throw new ToolLoopException("maxIterations must be positive");
        }
    }

    public void setEventConsumer(Consumer<AgentEvent> eventConsumer) {
        this.eventConsumer = eventConsumer == null ? event -> {} : eventConsumer;
    }

    public void emitEvent(AgentEventType type, String content) {
        eventConsumer.accept(AgentEvent.now(type, sessionId, runId, content));
    }

    public SkillGuard guard() {
        return guard;
    }

    public void setGuard(SkillGuard guard) {
        this.guard = guard == null ? SkillGuard.NONE : guard;
    }

    public int runEndGateNudgeCount() {
        return runEndGateNudgeCount;
    }

    public void resetRunEndGateNudgeCount() {
        this.runEndGateNudgeCount = 0;
    }

    public void incrementRunEndGateNudgeCount() {
        this.runEndGateNudgeCount++;
    }

    /**
     * Signal cancellation from an external thread. The orchestrator loop polls
     * {@link #isCancelRequested()} and bails cleanly on the next checkpoint. Safe to call
     * repeatedly — only the first call wins the reason.
     */
    public void requestCancel(String reason) {
        if (cancelRequested.compareAndSet(false, true)) {
            cancelReason.set(reason == null || reason.isBlank() ? "cancelled by user" : reason);
            // Push a value to the reactive sink so any stream subscribed via takeUntilOther
            // terminates right now instead of waiting for its own completion. If the sink
            // already completed (double cancel, unlikely) just swallow — the flag wins.
            cancelSink.tryEmitValue(Boolean.TRUE);
        }
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public String cancelReason() {
        return cancelReason.get();
    }

    /**
     * Signal usable with {@code Flux#takeUntilOther} / {@code Mono#takeUntilOther} so that
     * long-running reactive pipelines (notably the LLM SSE stream) abort immediately when
     * the user presses terminate, instead of waiting for the current HTTP response to drain.
     * Safe to subscribe multiple times — Sinks.One caches the value so late subscribers still
     * receive the cancel signal once it fires.
     */
    public Mono<Object> cancelSignal() {
        return cancelSink.asMono();
    }
}
