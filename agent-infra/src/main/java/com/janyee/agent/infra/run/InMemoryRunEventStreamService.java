package com.janyee.agent.infra.run;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.runtime.run.AgentEventFanout;
import com.janyee.agent.runtime.run.RunEventStreamService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内事件流的中央枢纽。每条事件:
 * <ol>
 *   <li>追到本 run 的内存 history(给 /api/runs/{id}/event-snapshot 用作刷新历史回灌)</li>
 *   <li>推到本 run 的 multicast sink(给 /api/runs/{id}/replay-and-subscribe 用作老 SSE 客户端)</li>
 *   <li>调一次 {@link AgentEventFanout#fanout(AgentEvent)} —— 由 UserEventHub 接管,按用户权限
 *       fan-out 到所有有权看的 WebSocket 连接</li>
 * </ol>
 *
 * <p>之前的 per-session sink 已经被删除:session 维度的分发现在通过 UserEventHub 集中做,
 * 权限过滤、跨 session 共视(多管理员同时看一个会话)、单条 WS 接所有事件,都在 hub 这一处实现。
 * 这里只保留 per-run sink 供刷新 / 重放使用。</p>
 */
@Service
public class InMemoryRunEventStreamService implements RunEventStreamService {

    private static final int MAX_EVENTS_PER_RUN = 1000;

    private final Map<String, RunEventStream> streams = new ConcurrentHashMap<>();
    private final ObjectProvider<AgentEventFanout> fanoutProvider;

    public InMemoryRunEventStreamService(ObjectProvider<AgentEventFanout> fanoutProvider) {
        this.fanoutProvider = fanoutProvider;
    }

    @Override
    public void publish(AgentEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        RunEventStream stream = streams.computeIfAbsent(event.runId(), ignored -> new RunEventStream());
        stream.publish(event);
        // 用户级扇出:每条事件走一次 UserEventHub。ObjectProvider lazy resolve 是为了避免循环 bean
        // 依赖(UserEventHub 依赖 SessionRepository 在 agent-infra,反过来又被 publisher 调用)。
        AgentEventFanout fanout = fanoutProvider.getIfAvailable();
        if (fanout != null) {
            fanout.fanout(event);
        }
    }

    @Override
    public Flux<AgentEvent> replayAndSubscribe(String runId) {
        if (runId == null || runId.isBlank()) {
            return Flux.empty();
        }
        RunEventStream stream = streams.computeIfAbsent(runId, ignored -> new RunEventStream());
        return stream.replayAndSubscribe();
    }

    @Override
    public java.util.List<AgentEvent> snapshot(String runId) {
        if (runId == null || runId.isBlank()) {
            return java.util.List.of();
        }
        RunEventStream stream = streams.get(runId);
        if (stream == null) {
            return java.util.List.of();
        }
        return stream.snapshot();
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

        synchronized java.util.List<AgentEvent> snapshot() {
            return new ArrayList<>(history);
        }
    }
}
