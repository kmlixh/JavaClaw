package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.AgentEvent;

/**
 * 进程内的事件扇出接口。{@link RunEventStreamService} 每次 publish 一条事件都同步调一次。
 *
 * <p>抽出这个接口的动机:agent-infra 不能反向依赖 agent-web。具体的扇出器(目前是
 * {@code UserEventHub},按用户权限分发到 WebSocket 客户端)是 web 层的事，但 service 层需要
 * 一个可注入的扩展点。任何 @Component 实现都会被 Spring 注入到所有 service 层的 publisher。</p>
 *
 * <p>实现必须是非阻塞、不抛、不慢:</p>
 * <ul>
 *   <li>publisher 调用频率很高(每条 LLM token 都发),fanout 内不能 blocking I/O</li>
 *   <li>任何 RuntimeException 都会污染 publish 调用栈,实现需要自己 try/catch</li>
 *   <li>没有连接 / 无人订阅时直接 no-op return,别在 hot path 上做 work</li>
 * </ul>
 */
public interface AgentEventFanout {

    /** 同步 fan-out。会被 RunEventStreamService.publish() 调用,实现禁止抛异常。 */
    void fanout(AgentEvent event);

    /**
     * 空实现兜底:测试 / 启动早期 fanout bean 还没注入完成时,publisher 用这个默认就行。
     */
    AgentEventFanout NOOP = event -> { };
}
