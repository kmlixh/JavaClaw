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
    RUN_CANCELLED,
    // run 结束时携带 token 用量。content 是 JSON {"prompt":N,"completion":N,"total":N}。
    // 供应商不返 usage 时不发该 event;前端在对应 assistant 气泡末尾渲染"本次消耗 N tokens"。
    TOKEN_USAGE
}
