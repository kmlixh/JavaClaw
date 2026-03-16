package com.janyee.agent.infra.runtime;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.memory.MemoryService;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.runtime.loop.ToolLoopIteration;
import com.janyee.agent.runtime.loop.ToolLoopOrchestrator;
import com.janyee.agent.runtime.loop.ToolLoopResult;
import com.janyee.agent.runtime.loop.ToolLoopState;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.runtime.run.RunRecordService;
import com.janyee.agent.runtime.session.SessionLockManager;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SimpleAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgentRunner.class);

    private final PromptAssembler promptAssembler;
    private final ToolLoopOrchestrator toolLoopOrchestrator;
    private final SessionService sessionService;
    private final SessionTranscriptService transcriptService;
    private final SessionLockManager sessionLockManager;
    private final AgentPlatformProperties properties;
    private final RunRecordService runRecordService;
    private final MemoryService memoryService;

    public SimpleAgentRunner(
            PromptAssembler promptAssembler,
            ToolLoopOrchestrator toolLoopOrchestrator,
            SessionService sessionService,
            SessionTranscriptService transcriptService,
            SessionLockManager sessionLockManager,
            AgentPlatformProperties properties,
            RunRecordService runRecordService,
            MemoryService memoryService
    ) {
        this.promptAssembler = promptAssembler;
        this.toolLoopOrchestrator = toolLoopOrchestrator;
        this.sessionService = sessionService;
        this.transcriptService = transcriptService;
        this.sessionLockManager = sessionLockManager;
        this.properties = properties;
        this.runRecordService = runRecordService;
        this.memoryService = memoryService;
    }

    @Override
    public Flux<AgentEvent> run(RunRequest request) {
        return Flux.create(sink ->
                        Schedulers.boundedElastic().schedule(() -> executeRun(request, sink)),
                FluxSink.OverflowStrategy.BUFFER
        );
    }

    private void executeRun(RunRequest request, FluxSink<AgentEvent> sink) {
        String runId = request.runId() != null && !request.runId().isBlank()
                ? request.runId()
                : UUID.randomUUID().toString();
        ToolLoopContext context = null;
        log.info("agent.run.start runId={}, sessionId={}, agentId={}, userId={}, resume={}, llmConfigId={}, messageLength={}",
                runId, request.sessionId(), request.agentId(), request.userId(), request.resume(),
                request.llmConfigId(), request.message() != null ? request.message().length() : 0);
        if (!sessionLockManager.tryLock(request.sessionId(), Duration.ofSeconds(30))) {
            log.warn("agent.run.lock_failed runId={}, sessionId={}", runId, request.sessionId());
            runRecordService.updateStatus(runId, RunStatus.FAILED, "session is busy");
            emit(sink, AgentEventType.RUN_FAILED, request.sessionId(), runId, "session is busy");
            sink.complete();
            return;
        }

        try {
            runRecordService.updateStatus(runId, RunStatus.RECEIVED, "run started");
            emit(sink, AgentEventType.RUN_STARTED, request.sessionId(), runId, "run started");
            sessionService.ensureSession(request.sessionId(), request.agentId(), request.userId());
            if (!request.resume()) {
                transcriptService.appendUserMessage(request.sessionId(), runId, request.message());
            }

            PromptContext promptContext = promptAssembler.assemble(request);
            log.info("agent.run.context_built runId={}, sessionId={}, promptLength={}",
                    runId, request.sessionId(), promptContext.assembledPrompt() != null ? promptContext.assembledPrompt().length() : 0);
            runRecordService.updateStatus(runId, RunStatus.CONTEXT_BUILT, "context built");
            emit(sink, AgentEventType.RUN_STATUS, request.sessionId(), runId, "context built");
            context = new ToolLoopContext(
                    request,
                    runId,
                    promptContext,
                    properties.runtime().maxToolIterations()
            );
            runRecordService.updateStatus(runId, RunStatus.MODEL_RUNNING, "model running");
            emit(sink, AgentEventType.RUN_STATUS, request.sessionId(), runId, "model running");
            ToolLoopResult loopResult = toolLoopOrchestrator.execute(context);
            List<ToolCallOutcome> displayOutcomes = collapseDisplayOutcomes(loopResult.toolOutcomes());
            String finalAssistantText = sanitizeAssistantText(loopResult.finalAssistantText(), displayOutcomes);
            log.info("agent.run.loop_finished runId={}, sessionId={}, finalState={}, success={}, toolCalls={}, assistantLength={}, error={}",
                    runId, request.sessionId(), loopResult.finalState(), loopResult.success(),
                    loopResult.toolOutcomes().size(),
                    finalAssistantText != null ? finalAssistantText.length() : 0,
                    loopResult.errorMessage());
            for (ToolCallOutcome outcome : displayOutcomes) {
                String toolContent = outcome.toolResult() != null
                        ? outcome.toolResult().summary()
                        : outcome.errorMessage();
                transcriptService.appendToolMessage(
                        request.sessionId(),
                        runId,
                        outcome.request().toolName(),
                        outcome.request().argumentsJson(),
                        outcome.toolResult() != null ? outcome.toolResult().dataJson() : null,
                        toolContent != null ? toolContent : ""
                );
            }
            emitLoopResultEvents(sink, request.sessionId(), runId, loopResult, displayOutcomes, finalAssistantText);

            if (!finalAssistantText.isBlank()) {
                transcriptService.appendAssistantMessage(request.sessionId(), runId, finalAssistantText);
                memoryService.saveNote(
                        request.agentId(),
                        request.sessionId(),
                        runId,
                        """
                        User: %s
                        Assistant: %s
                        """.formatted(request.message(), finalAssistantText),
                        "run_summary"
                );
            }

            RunStatus finalStatus = switch (loopResult.finalState()) {
                case COMPLETED -> RunStatus.COMPLETED;
                case WAITING_APPROVAL -> RunStatus.WAITING_APPROVAL;
                default -> RunStatus.FAILED;
            };
            runRecordService.updateStatus(runId, finalStatus, loopResult.success() ? "completed" : loopResult.errorMessage());
            log.info("agent.run.finish runId={}, sessionId={}, finalStatus={}", runId, request.sessionId(), finalStatus);
            sink.complete();
        } catch (Exception error) {
            log.error("agent.run.iterations runId={}, sessionId={}, iterations=\n{}",
                    runId,
                    request.sessionId(),
                    context != null ? formatIterations(context.iterations()) : "(none)");
            log.error("agent.run.error runId={}, sessionId={}", runId, request.sessionId(), error);
            runRecordService.updateStatus(runId, RunStatus.FAILED, error.getMessage());
            emit(sink, AgentEventType.RUN_FAILED, request.sessionId(), runId, error.getMessage());
            sink.complete();
        } finally {
            sessionLockManager.unlock(request.sessionId());
            log.debug("agent.run.unlock runId={}, sessionId={}", runId, request.sessionId());
        }
    }

    private void emitLoopResultEvents(
            FluxSink<AgentEvent> sink,
            String sessionId,
            String runId,
            ToolLoopResult result,
            List<ToolCallOutcome> displayOutcomes,
            String finalAssistantText
    ) {
        for (ToolCallOutcome outcome : displayOutcomes) {
            log.info("agent.run.tool runId={}, toolName={}, success={}, approvalRequired={}, error={}",
                    runId,
                    outcome.request().toolName(),
                    outcome.success(),
                    outcome.decision() != null && outcome.decision().approvalRequired(),
                    outcome.errorMessage());
            emit(sink, AgentEventType.TOOL_REQUESTED, sessionId, runId, outcome.request().toolName());
            emit(sink, AgentEventType.TOOL_STARTED, sessionId, runId, outcome.request().toolName());
            emit(sink, AgentEventType.TOOL_COMPLETED, sessionId, runId, toToolEventContent(outcome));
        }

        if (!finalAssistantText.isBlank()) {
            emit(sink, AgentEventType.TOKEN_DELTA, sessionId, runId, finalAssistantText);
        }

        if (result.finalState() == ToolLoopState.WAITING_APPROVAL) {
            String message = result.errorMessage() != null ? result.errorMessage() : "approval required";
            log.info("agent.run.waiting_approval runId={}, sessionId={}, message={}", runId, sessionId, message);
            emit(sink, AgentEventType.APPROVAL_REQUIRED, sessionId, runId, message);
            return;
        }

        if (result.success() && result.finalState() == ToolLoopState.COMPLETED) {
            emit(sink, AgentEventType.RUN_COMPLETED, sessionId, runId, "completed");
        } else {
            String message = result.errorMessage() != null ? result.errorMessage() : "run failed";
            emit(sink, AgentEventType.RUN_FAILED, sessionId, runId, message);
        }
    }

    private void emit(FluxSink<AgentEvent> sink, AgentEventType type, String sessionId, String runId, String content) {
        if (!sink.isCancelled()) {
            sink.next(AgentEvent.now(type, sessionId, runId, content));
        }
    }

    private String toToolEventContent(ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            return outcome.errorMessage() != null ? outcome.errorMessage() : "";
        }
        String summary = outcome.toolResult().summary() != null ? outcome.toolResult().summary() : "";
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson == null || dataJson.isBlank() || "{}".equals(dataJson.trim())) {
            return summary;
        }
        return summary + System.lineSeparator() + dataJson;
    }

    private List<ToolCallOutcome> collapseDisplayOutcomes(List<ToolCallOutcome> outcomes) {
        boolean hasRenderedOutput = outcomes.stream().anyMatch(this::isRenderedOutcome);
        if (!hasRenderedOutput) {
            return outcomes;
        }
        List<ToolCallOutcome> filtered = new ArrayList<>();
        for (ToolCallOutcome outcome : outcomes) {
            if ("db.query".equals(outcome.request().toolName())) {
                continue;
            }
            filtered.add(outcome);
        }
        return filtered;
    }

    private boolean isRenderedOutcome(ToolCallOutcome outcome) {
        String toolName = outcome.request().toolName();
        return "table.render".equals(toolName) || "chart.echarts".equals(toolName);
    }

    private String sanitizeAssistantText(String text, List<ToolCallOutcome> displayOutcomes) {
        if (text == null || text.isBlank()) {
            return "";
        }
        boolean hasRenderedOutput = displayOutcomes.stream().anyMatch(this::isRenderedOutcome);
        if (!hasRenderedOutput) {
            return text;
        }
        int cutIndex = -1;
        for (String marker : List.of("| 序号 |", "|------|", "\n|")) {
            int index = text.indexOf(marker);
            if (index >= 0 && (cutIndex < 0 || index < cutIndex)) {
                cutIndex = index;
            }
        }
        return cutIndex >= 0 ? text.substring(0, cutIndex).trim() : text.trim();
    }

    private String formatIterations(List<ToolLoopIteration> iterations) {
        if (iterations == null || iterations.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolLoopIteration iteration : iterations) {
            builder.append("#").append(iteration.iterationNo())
                    .append(" start=").append(iteration.startState())
                    .append(" end=").append(iteration.endState())
                    .append(System.lineSeparator())
                    .append("  model=").append(iteration.modelRequestSummary() == null ? "" : iteration.modelRequestSummary())
                    .append(System.lineSeparator());
            if (iteration.toolCallRequest() != null) {
                builder.append("  tool=").append(iteration.toolCallRequest().toolName())
                        .append(System.lineSeparator())
                        .append("  args=").append(iteration.toolCallRequest().argumentsJson() == null ? "" : iteration.toolCallRequest().argumentsJson())
                        .append(System.lineSeparator());
            }
            if (iteration.decision() != null) {
                builder.append("  decision allowed=").append(iteration.decision().allowed())
                        .append(", approvalRequired=").append(iteration.decision().approvalRequired())
                        .append(", normalizedTool=").append(iteration.decision().normalizedToolName() == null ? "" : iteration.decision().normalizedToolName())
                        .append(", reason=").append(iteration.decision().reason() == null ? "" : iteration.decision().reason())
                        .append(System.lineSeparator());
            }
            if (iteration.outcome() != null) {
                builder.append("  outcome success=").append(iteration.outcome().success())
                        .append(", durationMs=").append(iteration.outcome().durationMillis())
                        .append(", error=").append(iteration.outcome().errorMessage() == null ? "" : iteration.outcome().errorMessage())
                        .append(System.lineSeparator());
                if (iteration.outcome().toolResult() != null) {
                    builder.append("  summary=").append(iteration.outcome().toolResult().summary() == null ? "" : iteration.outcome().toolResult().summary())
                            .append(System.lineSeparator());
                }
            }
        }
        return builder.toString().trim();
    }
}
