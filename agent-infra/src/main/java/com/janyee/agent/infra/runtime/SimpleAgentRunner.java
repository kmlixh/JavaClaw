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
import com.janyee.agent.runtime.loop.ToolLoopOrchestrator;
import com.janyee.agent.runtime.loop.ToolLoopResult;
import com.janyee.agent.runtime.loop.ToolLoopState;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.runtime.run.RunRecordService;
import com.janyee.agent.runtime.session.SessionLockManager;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SimpleAgentRunner implements AgentRunner {

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
        return Mono.fromCallable(() -> executeRun(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private List<AgentEvent> executeRun(RunRequest request) {
        String runId = request.runId() != null && !request.runId().isBlank()
                ? request.runId()
                : UUID.randomUUID().toString();
        if (!sessionLockManager.tryLock(request.sessionId(), Duration.ofSeconds(30))) {
            runRecordService.updateStatus(runId, RunStatus.FAILED, "session is busy");
            return List.of(AgentEvent.now(
                    AgentEventType.RUN_FAILED,
                    request.sessionId(),
                    runId,
                    "session is busy"
            ));
        }

        try {
            runRecordService.updateStatus(runId, RunStatus.RECEIVED, "run started");
            sessionService.ensureSession(request.sessionId(), request.agentId(), request.userId());
            if (!request.resume()) {
                transcriptService.appendUserMessage(request.sessionId(), runId, request.message());
            }

            PromptContext promptContext = promptAssembler.assemble(request);
            runRecordService.updateStatus(runId, RunStatus.CONTEXT_BUILT, "context built");
            ToolLoopContext context = new ToolLoopContext(
                    request,
                    runId,
                    promptContext,
                    properties.runtime().maxToolIterations()
            );
            runRecordService.updateStatus(runId, RunStatus.MODEL_RUNNING, "model running");
            ToolLoopResult loopResult = toolLoopOrchestrator.execute(context);
            List<AgentEvent> events = toEvents(request.sessionId(), runId, loopResult);

            if (!loopResult.finalAssistantText().isBlank()) {
                transcriptService.appendAssistantMessage(request.sessionId(), runId, loopResult.finalAssistantText());
                memoryService.saveNote(
                        request.agentId(),
                        request.sessionId(),
                        runId,
                        """
                        User: %s
                        Assistant: %s
                        """.formatted(request.message(), loopResult.finalAssistantText()),
                        "run_summary"
                );
            }

            RunStatus finalStatus = switch (loopResult.finalState()) {
                case COMPLETED -> RunStatus.COMPLETED;
                case WAITING_APPROVAL -> RunStatus.WAITING_APPROVAL;
                default -> RunStatus.FAILED;
            };
            runRecordService.updateStatus(runId, finalStatus, loopResult.success() ? "completed" : loopResult.errorMessage());

            return events;
        } catch (Exception error) {
            runRecordService.updateStatus(runId, RunStatus.FAILED, error.getMessage());
            return List.of(
                    AgentEvent.now(AgentEventType.RUN_STARTED, request.sessionId(), runId, "run started"),
                    AgentEvent.now(AgentEventType.RUN_FAILED, request.sessionId(), runId, error.getMessage())
            );
        } finally {
            sessionLockManager.unlock(request.sessionId());
        }
    }

    private List<AgentEvent> toEvents(String sessionId, String runId, ToolLoopResult result) {
        List<AgentEvent> events = new ArrayList<>();
        events.add(AgentEvent.now(AgentEventType.RUN_STARTED, sessionId, runId, "run started"));
        events.add(AgentEvent.now(AgentEventType.RUN_STATUS, sessionId, runId, "context built"));
        events.add(AgentEvent.now(AgentEventType.RUN_STATUS, sessionId, runId, "model running"));

        for (ToolCallOutcome outcome : result.toolOutcomes()) {
            events.add(AgentEvent.now(
                    AgentEventType.TOOL_REQUESTED,
                    sessionId,
                    runId,
                    outcome.request().toolName()
            ));
            events.add(AgentEvent.now(
                    AgentEventType.TOOL_STARTED,
                    sessionId,
                    runId,
                    outcome.request().toolName()
            ));
            String toolSummary = outcome.toolResult() != null
                    ? outcome.toolResult().summary()
                    : outcome.errorMessage();
            events.add(AgentEvent.now(
                    AgentEventType.TOOL_COMPLETED,
                    sessionId,
                    runId,
                    toolSummary
            ));
        }

        if (!result.finalAssistantText().isBlank()) {
            events.add(AgentEvent.now(AgentEventType.TOKEN_DELTA, sessionId, runId, result.finalAssistantText()));
        }

        if (result.finalState() == ToolLoopState.WAITING_APPROVAL) {
            String message = result.errorMessage() != null ? result.errorMessage() : "approval required";
            events.add(AgentEvent.now(AgentEventType.APPROVAL_REQUIRED, sessionId, runId, message));
            return events;
        }

        if (result.success() && result.finalState() == ToolLoopState.COMPLETED) {
            events.add(AgentEvent.now(AgentEventType.RUN_COMPLETED, sessionId, runId, "completed"));
        } else {
            String message = result.errorMessage() != null ? result.errorMessage() : "run failed";
            events.add(AgentEvent.now(AgentEventType.RUN_FAILED, sessionId, runId, message));
        }
        return events;
    }
}
