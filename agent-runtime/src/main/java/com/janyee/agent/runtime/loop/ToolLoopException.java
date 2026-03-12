package com.janyee.agent.runtime.loop;

public class ToolLoopException extends RuntimeException {
    public ToolLoopException(String message) {
        super(message);
    }

    public ToolLoopException(String message, Throwable cause) {
        super(message, cause);
    }
}
