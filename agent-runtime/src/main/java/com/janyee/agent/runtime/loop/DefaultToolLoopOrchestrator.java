package com.janyee.agent.runtime.loop;

import com.janyee.agent.security.ApprovalService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Optional;

@Component
public class DefaultToolLoopOrchestrator implements ToolLoopOrchestrator {

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

        while (true) {
            validateIterationLimit(context);
            context.advanceState(context.iterationCount() == 0 ? ToolLoopState.MODEL_REQUESTING : ToolLoopState.MODEL_RESUMING);

            ModelTurnResult modelTurnResult = modelTurnExecutor.executeTurn(context);
            context.setLastModelRawOutput(modelTurnResult.fullText());
            context.advanceState(ToolLoopState.MODEL_STREAMING);

            Optional<ToolCallRequest> toolCallRequest = toolCallDetector.detect(modelTurnResult);
            if (toolCallRequest.isEmpty()) {
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
            context.advanceState(ToolLoopState.TOOL_POLICY_CHECKING);
            ToolCallDecision decision = toolLoopPolicy.evaluate(context, toolCallRequest.get());
            toolAuditService.recordPolicyDecision(context, toolCallRequest.get(), decision);

            if (!decision.allowed()) {
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
            ToolCallOutcome outcome = toolExecutor.execute(context, toolCallRequest.get(), decision);
            toolAuditService.recordExecutionOutcome(context, outcome);
            context.addToolOutcome(outcome);
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
}
