package com.janyee.agent.domain;

public enum AgentEventType {
    RUN_STARTED,
    RUN_STATUS,
    TOKEN_DELTA,
    TOOL_REQUESTED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    APPROVAL_REQUIRED,
    PLAN_UPDATED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED
}
