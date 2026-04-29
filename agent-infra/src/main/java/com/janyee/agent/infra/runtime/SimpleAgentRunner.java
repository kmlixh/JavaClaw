package com.janyee.agent.infra.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.memory.MemoryService;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.loop.RunPlanStore;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.runtime.loop.ToolLoopIteration;
import com.janyee.agent.runtime.loop.ToolLoopOrchestrator;
import com.janyee.agent.runtime.loop.ToolLoopResult;
import com.janyee.agent.runtime.loop.ToolLoopState;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.runtime.run.LiveRunRegistry;
import com.janyee.agent.runtime.run.RunCancellationRegistry;
import com.janyee.agent.runtime.run.RunEventStreamService;
import com.janyee.agent.runtime.run.RunRecordService;
import com.janyee.agent.runtime.session.SessionLockManager;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.runtime.skill.SkillGuardResolver;
import com.janyee.agent.runtime.skill.SkillGuardStore;
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
import java.util.regex.Pattern;

@Component
public class SimpleAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgentRunner.class);
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)\"(password|apiKey|api_key|token|accessToken|access_token)\"\\s*:\\s*\"([^\"]*)\""
    );
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)(password|apiKey|api_key|token|accessToken|access_token)\\s*=\\s*([^,\\s\\]}]+)"
    );

    private final PromptAssembler promptAssembler;
    private final ToolLoopOrchestrator toolLoopOrchestrator;
    private final SessionService sessionService;
    private final SessionTranscriptService transcriptService;
    private final SessionLockManager sessionLockManager;
    private final AgentPlatformProperties properties;
    private final RunRecordService runRecordService;
    private final RunEventStreamService runEventStreamService;
    private final MemoryService memoryService;
    private final RunPlanStore runPlanStore;
    private final SkillGuardResolver skillGuardResolver;
    private final SkillGuardStore skillGuardStore;
    private final LiveRunRegistry liveRunRegistry;
    private final RunCancellationRegistry cancellationRegistry;
    private final com.janyee.agent.infra.skill.SkillAgentMismatchChecker mismatchChecker;
    private final com.janyee.agent.infra.skill.SkillPlanSeeder skillPlanSeeder;
    private final PlanPreflightExecutor planPreflightExecutor;
    private final ObjectMapper objectMapper;

    public SimpleAgentRunner(
            PromptAssembler promptAssembler,
            ToolLoopOrchestrator toolLoopOrchestrator,
            SessionService sessionService,
            SessionTranscriptService transcriptService,
            SessionLockManager sessionLockManager,
            AgentPlatformProperties properties,
            RunRecordService runRecordService,
            RunEventStreamService runEventStreamService,
            MemoryService memoryService,
            RunPlanStore runPlanStore,
            SkillGuardResolver skillGuardResolver,
            SkillGuardStore skillGuardStore,
            LiveRunRegistry liveRunRegistry,
            RunCancellationRegistry cancellationRegistry,
            com.janyee.agent.infra.skill.SkillAgentMismatchChecker mismatchChecker,
            com.janyee.agent.infra.skill.SkillPlanSeeder skillPlanSeeder,
            PlanPreflightExecutor planPreflightExecutor,
            ObjectMapper objectMapper
    ) {
        this.promptAssembler = promptAssembler;
        this.toolLoopOrchestrator = toolLoopOrchestrator;
        this.sessionService = sessionService;
        this.transcriptService = transcriptService;
        this.sessionLockManager = sessionLockManager;
        this.properties = properties;
        this.runRecordService = runRecordService;
        this.runEventStreamService = runEventStreamService;
        this.memoryService = memoryService;
        this.runPlanStore = runPlanStore;
        this.skillGuardResolver = skillGuardResolver;
        this.skillGuardStore = skillGuardStore;
        this.liveRunRegistry = liveRunRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.mismatchChecker = mismatchChecker;
        this.skillPlanSeeder = skillPlanSeeder;
        this.planPreflightExecutor = planPreflightExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<AgentEvent> run(RunRequest request) {
        // boundedElastic 是新线程,ThreadLocal 不会跨过来。如果不在新线程上重建
        // SecurityContextHolder,下游所有 SecurityContextHolder.current() 都会 fallback
        // 到 anonymousSystemAdmin (admin / system / system-default),session/run
        // 的 tenant_id 全部落到 'system',xmap embed 列表永远查不到自己的会话。
        // 这里用 RunRequest 携带的身份三元组(controller 在请求线程上写进去的)在
        // 新线程上重建 principal,executeRun 结束后清理。
        return Flux.create(sink ->
                        Schedulers.boundedElastic().schedule(() -> {
                            com.janyee.agent.infra.auth.AuthPrincipal restored =
                                    rebuildPrincipal(request);
                            if (restored != null) {
                                com.janyee.agent.infra.auth.SecurityContextHolder.setCurrent(restored);
                            }
                            try {
                                executeRun(request, sink);
                            } finally {
                                com.janyee.agent.infra.auth.SecurityContextHolder.clear();
                            }
                        }),
                FluxSink.OverflowStrategy.BUFFER
        );
    }

    /**
     * 用 RunRequest 上的身份三元组重建一个最小可用的 AuthPrincipal。三元组缺失的话
     * 返回 null,executeRun 跑在 ThreadLocal 空(SecurityContextHolder.current() 走匿名兜底)
     * 的状态下 —— 跟改造前老行为一致,不会放大风险。
     */
    private com.janyee.agent.infra.auth.AuthPrincipal rebuildPrincipal(RunRequest request) {
        String userId = request.authUserId();
        String tenantId = request.authTenantId();
        String appId = request.authAppId();
        if ((userId == null || userId.isBlank())
                && (tenantId == null || tenantId.isBlank())
                && (appId == null || appId.isBlank())) {
            return null;
        }
        return new com.janyee.agent.infra.auth.AuthPrincipal(
                userId == null ? "" : userId,
                tenantId == null ? "system" : tenantId,
                appId == null ? "system-default" : appId,
                java.util.Set.of(),
                false
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

        liveRunRegistry.register(runId);
        try {
            runRecordService.updateStatus(runId, RunStatus.RECEIVED, "run started");
            emit(sink, AgentEventType.RUN_STARTED, request.sessionId(), runId, "run started");
            sessionService.ensureSession(request.sessionId(), request.agentId(), request.userId());
            if (!request.resume()) {
                String referencesJson = serializeForPersistence(request.references());
                String attachmentsJson = serializeForPersistence(request.attachments());
                transcriptService.appendUserMessage(
                        request.sessionId(), runId, request.message(),
                        referencesJson, attachmentsJson
                );
            }

            // Plan B bolt-on: fail fast if user picked an agent that doesn't own the skill
            // their message is clearly asking for. Prevents the "LLM loops 30 iterations then
            // gives up" pattern observed in real sessions.
            if (!request.resume()) {
                var mismatch = mismatchChecker.detect(request.agentId(), request.message());
                if (mismatch.isPresent()) {
                    String msg = mismatch.get().friendlyMessage(request.agentId());
                    log.warn("agent.run.skill_agent_mismatch runId={}, agentId={}, detail={}",
                            runId, request.agentId(), msg);
                    runRecordService.updateStatus(runId, RunStatus.FAILED, msg);
                    transcriptService.appendAssistantMessage(request.sessionId(), runId, msg);
                    emit(sink, AgentEventType.RUN_FAILED, request.sessionId(), runId, msg);
                    sink.complete();
                    return;
                }
            }

            PromptContext promptContext = promptAssembler.assemble(request);
            log.info("agent.run.context_built runId={}, sessionId={}, promptLength={}",
                    runId, request.sessionId(), promptContext.assembledPrompt() != null ? promptContext.assembledPrompt().length() : 0);
            runRecordService.updateStatus(runId, RunStatus.CONTEXT_BUILT, "context built");
            emit(sink, AgentEventType.RUN_STATUS, request.sessionId(), runId, "phase=CONTEXT context built");
            context = new ToolLoopContext(
                    request,
                    runId,
                    promptContext,
                    properties.runtime().maxToolIterations()
            );
            runPlanStore.register(runId, context.runPlan());
            cancellationRegistry.register(runId, context);
            SkillGuard guard = skillGuardResolver.resolve(request.agentId(), request.message());
            context.setGuard(guard);
            boolean planSeeded = false;
            if (!guard.isEmpty()) {
                skillGuardStore.register(runId, guard);
                log.info("agent.run.guard_registered runId={}, whitelistSize={}, requiredStepIds={}, skills={}",
                        runId, guard.whitelistTables().size(), guard.requiredPlanStepIds(), guard.contributingSkills());
                // 激活的 skill 要求 plan 时,直接帮 LLM 种好 plan,LLM 只需 plan.update 推进
                // step 状态。避免 LLM 漏调 plan.create 导致跑十几条 db.query 后被 guard 在
                // artifact 那一步硬拒(该项目 memory 里 feedback_enforcement_over_prompt
                // 明确偏好硬约束优于 prompt 调教)。
                planSeeded = skillPlanSeeder.seedIfNeeded(runId, context.runPlan(), guard);
            }
            context.setEventConsumer(event -> emit(sink, event.type(), event.sessionId(), event.runId(), event.content()));
            if (planSeeded) {
                // 把刚种好的 plan 推给前端:走 PLAN_UPDATED 事件就能让面板一开 run 就显示完整的 5 步,
                // 不用等 LLM 首次 plan.update 才能看到。snapshot JSON 由 RunPlan.toSnapshot() 生成。
                try {
                    emit(sink, AgentEventType.PLAN_UPDATED, request.sessionId(), runId,
                            objectMapper.writeValueAsString(context.runPlan().toSnapshot()));
                } catch (Exception error) {
                    log.warn("agent.run.plan_seed_event_failed runId={}, cause={}", runId, error.getMessage());
                }
                // L1 并行预执行:把"SQL 模板已物化、用户上下文完全就绪"的 wave 0 step 并发跑完,
                // LLM 再进 loop 时看到的就是带数据的 plan。对 skill.coverage.analysis 这种典型
                // "多路独立聚合"场景,能把端到端耗时从串行 N×单轮 压到 1×单轮 + 合成。
                try {
                    planPreflightExecutor.runWave0(context, request.attachments());
                } catch (Exception error) {
                    log.warn("agent.run.preflight_failed runId={}, cause={}", runId, error.getMessage());
                }
                // preflight 把若干 step 推到 COMPLETED 但不会自己 emit PLAN_UPDATED。再发一次
                // 让前端 panel 实时反映这些 step 的最新状态(否则前端只看到最初 seed 的全 PENDING
                // 快照,直到 LLM 第一次 plan.update 才会更新,看起来像"没动"导致用户怀疑没 plan)。
                try {
                    emit(sink, AgentEventType.PLAN_UPDATED, request.sessionId(), runId,
                            objectMapper.writeValueAsString(context.runPlan().toSnapshot()));
                } catch (Exception error) {
                    log.warn("agent.run.plan_after_preflight_event_failed runId={}, cause={}", runId, error.getMessage());
                }
            }
            runRecordService.updateStatus(runId, RunStatus.MODEL_RUNNING, "model running");
            emit(sink, AgentEventType.RUN_STATUS, request.sessionId(), runId, "phase=MODEL model running");
            ToolLoopResult loopResult = toolLoopOrchestrator.execute(context);
            List<ToolCallOutcome> displayOutcomes = collapseDisplayOutcomes(loopResult.toolOutcomes());
            String finalAssistantText = sanitizeAssistantText(loopResult.finalAssistantText(), displayOutcomes);
            log.info("agent.run.loop_finished runId={}, sessionId={}, finalState={}, success={}, toolCalls={}, assistantLength={}, error={}",
                    runId, request.sessionId(), loopResult.finalState(), loopResult.success(),
                    loopResult.toolOutcomes().size(),
                    finalAssistantText != null ? finalAssistantText.length() : 0,
                    loopResult.errorMessage());
            boolean renderedOutput = displayOutcomes.stream().anyMatch(this::isRenderedOutcome);
            if (renderedOutput && !finalAssistantText.isBlank()) {
                transcriptService.appendAssistantMessage(request.sessionId(), runId, finalAssistantText);
            }
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
            emitLoopResultEvents(sink, request.sessionId(), runId, loopResult, finalAssistantText, renderedOutput);

            if (!finalAssistantText.isBlank() && !renderedOutput) {
                transcriptService.appendAssistantMessage(request.sessionId(), runId, finalAssistantText);
            }
            // loop 正常返回(没抛异常)但业务失败 —— 需要把失败原因落库,否则刷新后 FAILED
            // 状态显示在 run 列表里,却没有任何对应的消息气泡,用户看着摸不着头脑。
            // COMPLETED / WAITING_APPROVAL / CANCELLED 三种"非失败"终止态各有自己的
            // 信号消息,这里只补 FAILED 分支。
            boolean isFailedFinal = !loopResult.success()
                    && loopResult.finalState() != ToolLoopState.WAITING_APPROVAL
                    && loopResult.finalState() != ToolLoopState.CANCELLED;
            if (isFailedFinal && finalAssistantText.isBlank()) {
                String msg = loopResult.errorMessage() != null ? loopResult.errorMessage() : "run failed";
                try {
                    transcriptService.appendAssistantMessage(request.sessionId(), runId, "[运行失败] " + msg);
                } catch (Exception persistError) {
                    log.warn("agent.run.failure_persist_failed runId={}, sessionId={}, cause={}",
                            runId, request.sessionId(), persistError.getMessage());
                }
            }
            if (!finalAssistantText.isBlank()) {
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
                case CANCELLED -> RunStatus.CANCELLED;
                default -> RunStatus.FAILED;
            };
            String statusDetail = loopResult.success() ? "completed"
                    : loopResult.finalState() == ToolLoopState.CANCELLED
                        ? (loopResult.errorMessage() != null ? loopResult.errorMessage() : "cancelled by user")
                        : loopResult.errorMessage();
            runRecordService.updateStatus(runId, finalStatus, statusDetail);
            log.info("agent.run.finish runId={}, sessionId={}, finalStatus={}", runId, request.sessionId(), finalStatus);
            sink.complete();
        } catch (Exception error) {
            log.error("agent.run.iterations runId={}, sessionId={}, iterations=\n{}",
                    runId,
                    request.sessionId(),
                    context != null ? formatIterations(context.iterations()) : "(none)");
            log.error("agent.run.error runId={}, sessionId={}", runId, request.sessionId(), error);
            String failureMessage = error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage()
                    : error.getClass().getSimpleName();
            runRecordService.updateStatus(runId, RunStatus.FAILED, failureMessage);
            // 把失败原因落进 session_message,让它跟普通 assistant 消息一样走持久化路径 ——
            // 刷新 / 切会话 / 重连后依然可见,不会再出现"报错一闪而过"的体验。
            // 落盘失败不应该淹没原始错误,try/catch 单独兜住。
            try {
                transcriptService.appendAssistantMessage(request.sessionId(), runId,
                        "[运行失败] " + failureMessage);
            } catch (Exception persistError) {
                log.warn("agent.run.failure_persist_failed runId={}, sessionId={}, cause={}",
                        runId, request.sessionId(), persistError.getMessage());
            }
            emit(sink, AgentEventType.RUN_FAILED, request.sessionId(), runId, failureMessage);
            sink.complete();
        } finally {
            runPlanStore.unregister(runId);
            skillGuardStore.unregister(runId);
            liveRunRegistry.unregister(runId);
            cancellationRegistry.unregister(runId);
            sessionLockManager.unlock(request.sessionId());
            log.debug("agent.run.unlock runId={}, sessionId={}", runId, request.sessionId());
        }
    }

    private void emitLoopResultEvents(
            FluxSink<AgentEvent> sink,
            String sessionId,
            String runId,
            ToolLoopResult result,
            String finalAssistantText,
            boolean renderedOutput
    ) {
        if (!finalAssistantText.isBlank() && !renderedOutput) {
            emit(sink, AgentEventType.TOKEN_DELTA, sessionId, runId, finalAssistantText);
        }

        if (result.finalState() == ToolLoopState.WAITING_APPROVAL) {
            String message = result.errorMessage() != null ? result.errorMessage() : "approval required";
            log.info("agent.run.waiting_approval runId={}, sessionId={}, message={}", runId, sessionId, message);
            emit(sink, AgentEventType.APPROVAL_REQUIRED, sessionId, runId, message);
            return;
        }

        if (result.finalState() == ToolLoopState.CANCELLED) {
            String message = result.errorMessage() != null ? result.errorMessage() : "cancelled by user";
            log.info("agent.run.cancelled runId={}, sessionId={}, message={}", runId, sessionId, message);
            emit(sink, AgentEventType.RUN_CANCELLED, sessionId, runId, message);
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
        AgentEvent event = AgentEvent.now(type, sessionId, runId, content);
        runEventStreamService.publish(event);
        if (!sink.isCancelled()) {
            sink.next(event);
        }
    }

    private List<ToolCallOutcome> collapseDisplayOutcomes(List<ToolCallOutcome> outcomes) {
        boolean hasRenderedOutput = outcomes.stream().anyMatch(this::isRenderedOutcome);
        if (!hasRenderedOutput) {
            return outcomes;
        }
        ToolCallOutcome preferredRendered = null;
        for (ToolCallOutcome outcome : outcomes) {
            if (!isRenderedOutcome(outcome)) {
                continue;
            }
            String toolName = outcome.request().toolName();
            if (preferredRendered == null) {
                preferredRendered = outcome;
                continue;
            }
            String preferredToolName = preferredRendered.request().toolName();
            if (toolName.equals(preferredToolName)) {
                preferredRendered = outcome;
            }
        }

        List<ToolCallOutcome> filtered = new ArrayList<>();
        for (ToolCallOutcome outcome : outcomes) {
            String toolName = outcome.request().toolName();
            if ("db.query".equals(toolName)) {
                continue;
            }
            if (isRenderedOutcome(outcome) && outcome != preferredRendered) {
                continue;
            }
            filtered.add(outcome);
        }
        return filtered;
    }

    private boolean isRenderedOutcome(ToolCallOutcome outcome) {
        // No standalone chart/table rendering tools — charts are hand-written ```echarts``` blocks
        // inside artifact.markdown, and tables are hand-written GFM tables. Nothing in the tool
        // outcome list counts as "a rendered block that displaces the assistant text". Left as
        // always-false so existing callers (filterRedundantRenderedOutcomes, sanitizeAssistantText)
        // simply no-op; kept as a method so we don't have to rip out call sites.
        return false;
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
                        .append("  args=").append(redactSecrets(iteration.toolCallRequest().argumentsJson() == null ? "" : iteration.toolCallRequest().argumentsJson()))
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

    private String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JSON_SECRET_PATTERN.matcher(value)
                .replaceAll("\"$1\":\"***\"");
        return KV_SECRET_PATTERN.matcher(redacted)
                .replaceAll("$1=***");
    }

    /**
     * 序列化 references / attachments 以落进 session_message。空列表返回 null(columns
     * 本来就是 nullable)避免在 DB 里堆一堆 "[]" 字符串占空间。序列化失败不让 run 挂掉
     * —— 只是这条消息的附件丢了,走重发时会按空数组处理,最坏是回退到"只有文本"的老行为。
     */
    private String serializeForPersistence(List<?> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.warn("transcript.persist.serialize_failed size={}, cause={}", items.size(), e.getMessage());
            return null;
        }
    }
}
