package com.janyee.agent.runtime.loop;

public class ToolExecutionFailedException extends ToolLoopException {
    public ToolExecutionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
