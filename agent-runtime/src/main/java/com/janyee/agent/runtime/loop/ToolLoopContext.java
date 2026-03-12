package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;

import java.util.ArrayList;
import java.util.List;

public class ToolLoopContext {

    private final String sessionId;
    private final String runId;
    private final String agentId;
    private final String userId;
    private final int maxIterations;
    private final PromptContext promptContext;
    private final StringBuilder assistantTextBuffer = new StringBuilder();
    private final List<ToolLoopIteration> iterations = new ArrayList<>();
    private final List<ToolCallOutcome> toolOutcomes = new ArrayList<>();

    private ToolLoopState state = ToolLoopState.INITIALIZING;
    private int iterationCount;
    private String lastModelRawOutput;
    private ToolCallRequest pendingToolCall;

    public ToolLoopContext(RunRequest request, String runId, PromptContext promptContext, int maxIterations) {
        this.sessionId = request.sessionId();
        this.runId = runId;
        this.agentId = request.agentId();
        this.userId = request.userId();
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

    public ToolLoopState state() {
        return state;
    }

    public int iterationCount() {
        return iterationCount;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public String currentPrompt() {
        if (lastModelRawOutput == null || lastModelRawOutput.isBlank()) {
            return promptContext.assembledPrompt();
        }
        return promptContext.assembledPrompt() + "\n\n" + lastModelRawOutput;
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
}
