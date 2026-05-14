package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.AgentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

public interface RunEventStreamService {
    /**
     * 写入一条 run 事件。会同时:
     * <ul>
     *   <li>追到 per-run history(给 /api/runs/{id}/event-snapshot 用)</li>
     *   <li>推到 per-run multicast sink(给老的 /api/chat/stream SSE 重放路径用)</li>
     *   <li>调一次 {@link AgentEventFanout} —— 由 UserEventHub 把事件按权限分发到登录用户的 WS</li>
     * </ul>
     */
    void publish(AgentEvent event);

    /** 给老的 per-run SSE / WS 重放路径用,新的 /ws/events 不走这条。 */
    Flux<AgentEvent> replayAndSubscribe(String runId);

    /**
     * 当前 run 内存里累积的事件历史快照,给 GET /api/runs/{id}/event-snapshot 用作刷新时把已经
     * 发生的事件回灌 UI。run 不存在 / 已 flush 出内存返回空列表。
     */
    List<AgentEvent> snapshot(String runId);
}
