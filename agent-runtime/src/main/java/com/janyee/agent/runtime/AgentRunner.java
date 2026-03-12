package com.janyee.agent.runtime;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.RunRequest;
import reactor.core.publisher.Flux;

public interface AgentRunner {
    Flux<AgentEvent> run(RunRequest request);
}
