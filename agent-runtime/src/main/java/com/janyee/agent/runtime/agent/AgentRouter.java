package com.janyee.agent.runtime.agent;

import com.janyee.agent.domain.AgentBinding;

public interface AgentRouter {
    AgentBinding route(AgentRouteRequest request);
}
