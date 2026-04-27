package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.AgentEvent;
import reactor.core.publisher.Flux;

public interface RunEventStreamService {
    void publish(AgentEvent event);

    Flux<AgentEvent> replayAndSubscribe(String runId);
}
