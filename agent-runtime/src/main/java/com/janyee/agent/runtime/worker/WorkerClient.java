package com.janyee.agent.runtime.worker;

public interface WorkerClient {
    WorkerTaskResult execute(WorkerTaskRequest request);
}
