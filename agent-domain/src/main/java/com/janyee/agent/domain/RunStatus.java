package com.janyee.agent.domain;

public enum RunStatus {
    RECEIVED,
    CONTEXT_BUILT,
    MODEL_RUNNING,
    TOOL_REQUESTED,
    TOOL_EXECUTING,
    TOOL_RESULT_APPENDED,
    COMPLETED,
    FAILED,
    WAITING_APPROVAL
}
