package com.janyee.agent.runtime.loop;

public class MaxToolIterationExceededException extends ToolLoopException {
    public MaxToolIterationExceededException(int current, int max) {
        super("max tool iteration exceeded: current=%d, max=%d".formatted(current, max));
    }
}
