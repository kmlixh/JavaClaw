package com.janyee.agent.runtime.worker;

public record WorkerTaskResult(
        boolean ok,
        String summary,
        String output,
        String error,
        long durationMillis
) {
}
