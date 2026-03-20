package com.janyee.agent.runtime.loop;

import com.janyee.agent.security.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Optional;

@Component
public class DefaultToolLoopOrchestrator implements ToolLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolLoopOrchestrator.class);

    private final ModelTurnExecutor modelTurnExecutor;
    private final ToolCallDetector toolCallDetector;
    private final ToolLoopPolicy toolLoopPolicy;
    private final ToolExecutor toolExecutor;
    private final ToolResultAppender toolResultAppender;
    private final ToolAuditService toolAuditService;
    private final ApprovalService approvalService;

    public DefaultToolLoopOrchestrator(
            ModelTurnExecutor modelTurnExecutor,
            ToolCallDetector toolCallDetector,
            ToolLoopPolicy toolLoopPolicy,
            ToolExecutor toolExecutor,
            ToolResultAppender toolResultAppender,
            ToolAuditService toolAuditService,
            ApprovalService approvalService
    ) {
        this.modelTurnExecutor = modelTurnExecutor;
        this.toolCallDetector = toolCallDetector;
        this.toolLoopPolicy = toolLoopPolicy;
        this.toolExecutor = toolExecutor;
        this.toolResultAppender = toolResultAppender;
        this.toolAuditService = toolAuditService;
        this.approvalService = approvalService;
    }

    @Override
    public ToolLoopResult execute(ToolLoopContext context) {
        context.advanceState(ToolLoopState.INITIALIZING);
        context.validateInvariant();
        log.info("tool.loop.start runId={}, sessionId={}, agentId={}, maxIterations={}",
                context.runId(), context.sessionId(), context.agentId(), context.maxIterations());

        while (true) {
            validateIterationLimit(context);
            context.advanceState(context.iterationCount() == 0 ? ToolLoopState.MODEL_REQUESTING : ToolLoopState.MODEL_RESUMING);

            ModelTurnResult modelTurnResult = modelTurnExecutor.executeTurn(context);
            context.setLastModelRawOutput(modelTurnResult.fullText());
            context.advanceState(ToolLoopState.MODEL_STREAMING);
            log.info("tool.loop.model_output runId={}, iteration={}, text={}",
                    context.runId(), context.iterationCount(), summarize(modelTurnResult.fullText()));

            Optional<ToolCallRequest> toolCallRequest = toolCallDetector.detect(modelTurnResult);
            if (toolCallRequest.isEmpty()) {
                log.info("tool.loop.no_tool_call runId={}, iteration={}, assistantText={}",
                        context.runId(), context.iterationCount(), summarize(modelTurnResult.fullText()));
                context.appendAssistantText(modelTurnResult.fullText());
                context.advanceState(ToolLoopState.COMPLETED);
                return new ToolLoopResult(
                        true,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        null
                );
            }

            context.setPendingToolCall(toolCallRequest.get());
            context.advanceState(ToolLoopState.TOOL_CALL_DETECTED);
            log.info("tool.loop.tool_detected runId={}, iteration={}, toolName={}, arguments={}",
                    context.runId(), context.iterationCount(),
                    toolCallRequest.get().toolName(),
                    summarize(toolCallRequest.get().argumentsJson()));
            context.advanceState(ToolLoopState.TOOL_POLICY_CHECKING);
            ToolCallDecision decision = toolLoopPolicy.evaluate(context, toolCallRequest.get());
            toolAuditService.recordPolicyDecision(context, toolCallRequest.get(), decision);
            log.info("tool.loop.policy_decision runId={}, toolName={}, allowed={}, approvalRequired={}, normalizedToolName={}, reason={}",
                    context.runId(),
                    toolCallRequest.get().toolName(),
                    decision.allowed(),
                    decision.approvalRequired(),
                    decision.normalizedToolName(),
                    decision.reason());

            Optional<ToolCallOutcome> repeatedOutcome = findRepeatedOutcome(context, decision);
            if (repeatedOutcome.isPresent()) {
                log.warn("tool.loop.repeated_call_detected runId={}, toolName={}, arguments={}",
                        context.runId(), decision.normalizedToolName(), summarize(decision.normalizedArgumentsJson()));
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        repeatedOutcome.get(),
                        ToolLoopState.TOOL_CALL_DETECTED,
                        repeatedOutcome.get().success() ? ToolLoopState.COMPLETED : ToolLoopState.FAILED
                ));
                context.appendAssistantText(renderRepeatedToolResult(decision, repeatedOutcome.get()));
                context.advanceState(repeatedOutcome.get().success() ? ToolLoopState.COMPLETED : ToolLoopState.FAILED);
                return new ToolLoopResult(
                        repeatedOutcome.get().success(),
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        repeatedOutcome.get().success() ? null : safe(repeatedOutcome.get().errorMessage())
                );
            }

            if (!decision.allowed()) {
                log.warn("tool.loop.blocked runId={}, toolName={}, reason={}",
                        context.runId(), toolCallRequest.get().toolName(), decision.reason());
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        null,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.FAILED
                ));
                context.advanceState(ToolLoopState.FAILED);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        decision.reason()
                );
            }

            if (decision.approvalRequired()) {
                String approvalRequestId = approvalService.createRequest(
                        context.runId(),
                        context.sessionId(),
                        context.agentId(),
                        decision.normalizedToolName(),
                        decision.normalizedArgumentsJson(),
                        decision.reason()
                );
                log.info("tool.loop.waiting_approval runId={}, toolName={}, approvalRequestId={}, reason={}",
                        context.runId(), decision.normalizedToolName(), approvalRequestId, decision.reason());
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        null,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.WAITING_APPROVAL
                ));
                context.advanceState(ToolLoopState.WAITING_APPROVAL);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        decision.reason() + ": " + approvalRequestId
                );
            }

            context.advanceState(ToolLoopState.TOOL_EXECUTING);
            log.info("tool.loop.executing runId={}, toolName={}, arguments={}",
                    context.runId(), decision.normalizedToolName(), summarize(decision.normalizedArgumentsJson()));
            ToolCallOutcome outcome = toolExecutor.execute(context, toolCallRequest.get(), decision);
            toolAuditService.recordExecutionOutcome(context, outcome);
            context.addToolOutcome(outcome);
            log.info("tool.loop.executed runId={}, toolName={}, success={}, durationMs={}, error={}, summary={}",
                    context.runId(),
                    decision.normalizedToolName(),
                    outcome.success(),
                    outcome.durationMillis(),
                    outcome.errorMessage(),
                    outcome.toolResult() != null ? summarize(outcome.toolResult().summary()) : "");
            if (shouldTerminateAfterRender(outcome)) {
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        outcome,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.COMPLETED
                ));
                String terminalSummary = renderTerminalSummary(outcome);
                context.appendAssistantText(terminalSummary);
                context.advanceState(ToolLoopState.COMPLETED);
                log.info("tool.loop.render_terminated runId={}, toolName={}, summary={}",
                        context.runId(),
                        decision.normalizedToolName(),
                        summarize(terminalSummary));
                return new ToolLoopResult(
                        true,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        null
                );
            }
            context.advanceState(ToolLoopState.TOOL_RESULT_APPENDING);
            toolResultAppender.append(context, outcome);
            context.incrementIteration();
            context.recordIteration(new ToolLoopIteration(
                    context.iterationCount(),
                    summarize(modelTurnResult.fullText()),
                    toolCallRequest.get(),
                    decision,
                    outcome,
                    ToolLoopState.TOOL_CALL_DETECTED,
                    ToolLoopState.MODEL_RESUMING
            ));
            context.clearPendingToolCall();
        }
    }

    private void validateIterationLimit(ToolLoopContext context) {
        if (context.iterationCount() >= context.maxIterations()) {
            log.error("tool.loop.max_iterations_exceeded runId={}, sessionId={}, current={}, max={}, iterations=\n{}",
                    context.runId(),
                    context.sessionId(),
                    context.iterationCount(),
                    context.maxIterations(),
                    formatIterations(context.iterations()));
            context.advanceState(ToolLoopState.FAILED);
            throw new MaxToolIterationExceededException(context.iterationCount(), context.maxIterations());
        }
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private Optional<ToolCallOutcome> findRepeatedOutcome(ToolLoopContext context, ToolCallDecision decision) {
        return context.toolOutcomes().stream()
                .filter(outcome -> outcome.decision() != null)
                .filter(outcome -> decision.normalizedToolName().equals(outcome.decision().normalizedToolName()))
                .filter(outcome -> safe(decision.normalizedArgumentsJson()).equals(safe(outcome.decision().normalizedArgumentsJson())))
                .reduce((first, second) -> second);
    }

    private String renderRepeatedToolResult(ToolCallDecision decision, ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            return "Tool " + decision.normalizedToolName() + " failed previously: " + safe(outcome.errorMessage());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Tool ").append(decision.normalizedToolName()).append(" result").append(System.lineSeparator());
        builder.append(safe(outcome.toolResult().summary()));
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson != null && !dataJson.isBlank() && !"{}".equals(dataJson.trim())) {
            builder.append(System.lineSeparator()).append(dataJson);
        }
        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean shouldTerminateAfterRender(ToolCallOutcome outcome) {
        if (outcome == null || !outcome.success() || outcome.toolResult() == null || outcome.decision() == null) {
            return false;
        }
        String toolName = outcome.decision().normalizedToolName();
        return "table.render".equals(toolName) || "chart.echarts".equals(toolName);
    }

    private String renderTerminalSummary(ToolCallOutcome outcome) {
        String toolName = outcome.decision() != null ? outcome.decision().normalizedToolName() : outcome.request().toolName();
        String summary = outcome.toolResult() != null ? safe(outcome.toolResult().summary()) : "";
        if ("chart.echarts".equals(toolName)) {
            return "图表已生成，请查看下方图表结果。";
        }
        if ("table.render".equals(toolName)) {
            return "表格已生成，请查看下方表格结果。";
        }
        return summary.isBlank() ? "结果已生成。" : summary;
    }

    private String formatIterations(java.util.List<ToolLoopIteration> iterations) {
        if (iterations == null || iterations.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolLoopIteration iteration : iterations) {
            builder.append("#").append(iteration.iterationNo())
                    .append(" start=").append(iteration.startState())
                    .append(" end=").append(iteration.endState())
                    .append(System.lineSeparator())
                    .append("  model=").append(safe(iteration.modelRequestSummary()))
                    .append(System.lineSeparator());
            if (iteration.toolCallRequest() != null) {
                builder.append("  tool=").append(iteration.toolCallRequest().toolName())
                        .append(System.lineSeparator())
                        .append("  args=").append(safe(iteration.toolCallRequest().argumentsJson()))
                        .append(System.lineSeparator());
            }
            if (iteration.decision() != null) {
                builder.append("  decision allowed=").append(iteration.decision().allowed())
                        .append(", approvalRequired=").append(iteration.decision().approvalRequired())
                        .append(", normalizedTool=").append(safe(iteration.decision().normalizedToolName()))
                        .append(", reason=").append(safe(iteration.decision().reason()))
                        .append(System.lineSeparator());
            }
            if (iteration.outcome() != null) {
                builder.append("  outcome success=").append(iteration.outcome().success())
                        .append(", durationMs=").append(iteration.outcome().durationMillis())
                        .append(", error=").append(safe(iteration.outcome().errorMessage()))
                        .append(System.lineSeparator());
                if (iteration.outcome().toolResult() != null) {
                    builder.append("  summary=").append(safe(iteration.outcome().toolResult().summary()))
                            .append(System.lineSeparator());
                }
            }
        }
        return builder.toString().trim();
    }
}
