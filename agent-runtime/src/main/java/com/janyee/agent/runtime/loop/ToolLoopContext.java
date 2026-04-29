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
            // Plan 监督:让 LLM 每一轮都看到"下一步具体做什么",别盯着已 COMPLETED 的 step
            // 重新发工具调用。preflight 把 wave-0 的 db.query 全跑完后,如果不显式提示,
            // LLM 经常会重复发同样的查询(它没意识到 plan 里 [COMPLETED] 标签的含义)。
            NextActionHint.suggest(runPlan).ifPresent(hint ->
                    prompt.append("\n\n[Next Action]\n").append(hint));
            // 兜底硬约束:任何在 [COMPLETED] 状态的 step 不要再发对应的工具调用 ——
            // 数据已经在 [Completed Queries] 里,直接用即可。这条配合 NextActionHint
            // 一起,把"先 plan.update IN_PROGRESS → 执行工作工具 → plan.update COMPLETED"
            // 这条标准节奏强行钉死,避免 LLM 自由发挥。
            prompt.append("\n[Plan Discipline] ")
                  .append("Do NOT re-issue a tool call for any step already marked [COMPLETED] — its outputs ")
                  .append("are in [Completed Queries] above, read from there. ")
                  .append("Advance plan strictly via plan.update: PENDING → IN_PROGRESS → COMPLETED. ")
                  .append("Only one step IN_PROGRESS at a time; finish it before starting the next.")
                  // Wave barrier 提示:每个 step 在 [Plan] 渲染里带 deps=[A,B] 列表,标识它依赖
                  // 谁。LLM 不准跳过 PENDING 的兄弟 step 直接启动下游 —— 如果一组数据 step
                  // 是兄弟(共同依赖一个根),它们可以并行进 IN_PROGRESS(实际目前只允许 1 个),
                  // 但下游 report step 必须等所有兄弟 step 都 COMPLETED 才能启动。
                  // PlanUpdateTool 会拒掉违规转换并告诉你具体哪个 dep 没满足。
                  .append(" DEPENDENCY BARRIER: each step lists its prerequisite step IDs as deps=[...] in the [Plan] view. ")
                  .append("Do NOT mark a downstream step IN_PROGRESS while any of its dependencies is still PENDING/IN_PROGRESS — ")
                  .append("finish every parallel sibling first, even if one of them already returned the data you wanted. ")
                  // Scope-mismatch 防呆:preflight 在没有 GeoJSON 时会跑全省级 SQL 并把
                  // step 标 COMPLETED,note=scope=NO_FILTER。LLM 之前会把这些数字直接当成
                  // 用户问的"西山区/某城市"答案写进报告 —— 这条规则强制要求 LLM 看到
                  // scope=NO_FILTER 时必须先重查再写报告,而不是"COMPLETED 就用"。
                  .append("SCOPE CHECK: if a [COMPLETED] step's note contains 'scope=NO_FILTER' AND the user asked about a specific city / district / area / map attachment ({地图N}), those preflight numbers are SCOPE-MISMATCHED — you MUST issue a new db.query restricted to the user's region before writing those values into any artifact.*; do not copy NO_FILTER aggregates into a region-specific report.");
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
                // 把对应 step 的 resultNote 里的 scope 标签贴到这行。LLM 之前只在 [Plan]
                // 里看到 note=scope=NO_FILTER,在 [Completed Queries] 里却看不出每条数字的范围,
                // 复制粘贴时就漏过了。每行 summary 自己带 scope 标签,LLM 想忽略都难。
                runPlan.find(s.stepId()).ifPresent(step -> {
                    String note = step.resultNote();
                    if (note != null && !note.isBlank()) {
                        int sep = note.indexOf(" | ");
                        String scopeFragment = sep > 0 ? note.substring(0, sep) : note;
                        if (scopeFragment.startsWith("scope=")) {
                            builder.append(" | ").append(scopeFragment);
                        }
                    }
                });
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
