package com.janyee.agent.infra.run;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.runtime.run.RunEventStreamService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryRunEventStreamService implements RunEventStreamService {

    private static final int MAX_EVENTS_PER_RUN = 1000;

    private final Map<String, RunEventStream> streams = new ConcurrentHashMap<>();

    @Override
    public void publish(AgentEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        RunEventStream stream = streams.computeIfAbsent(event.runId(), ignored -> new RunEventStream());
        stream.publish(event);
    }

    @Override
    public Flux<AgentEvent> replayAndSubscribe(String runId) {
        if (runId == null || runId.isBlank()) {
            return Flux.empty();
        }
        RunEventStream stream = streams.computeIfAbsent(runId, ignored -> new RunEventStream());
        return stream.replayAndSubscribe();
    }

    private static boolean isTerminal(AgentEvent event) {
        return event.type() == AgentEventType.RUN_COMPLETED || event.type() == AgentEventType.RUN_FAILED;
    }

    private static final class RunEventStream {
        private final Deque<AgentEvent> history = new ArrayDeque<>();
        private final Sinks.Many<AgentEvent> sink = Sinks.many().multicast().directBestEffort();
        private boolean terminal;

        synchronized void publish(AgentEvent event) {
            if (history.size() >= MAX_EVENTS_PER_RUN) {
                history.removeFirst();
            }
            history.addLast(event);
            if (!terminal) {
                sink.tryEmitNext(event);
            }
            if (isTerminal(event)) {
                terminal = true;
                sink.tryEmitComplete();
            }
        }

        synchronized Flux<AgentEvent> replayAndSubscribe() {
            Flux<AgentEvent> replay = Flux.fromIterable(new ArrayList<>(history));
            return terminal ? replay : replay.concatWith(sink.asFlux());
        }
    }
}
