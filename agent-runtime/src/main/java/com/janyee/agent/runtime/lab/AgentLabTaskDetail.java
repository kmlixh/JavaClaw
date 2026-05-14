package com.janyee.agent.runtime.lab;

import java.util.List;

/** 详情接口返回 task + 全部 iterations,前端一次性渲染时间线。 */
public record AgentLabTaskDetail(
        AgentLabTaskView task,
        List<AgentLabIterationView> iterations
) {
}
