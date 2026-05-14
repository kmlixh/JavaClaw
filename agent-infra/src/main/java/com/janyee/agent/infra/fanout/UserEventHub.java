package com.janyee.agent.infra.fanout;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.SessionVisibility;
import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.runtime.run.AgentEventFanout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内所有已登录用户 WebSocket 连接的总线。
 *
 * <p>每个连接通过 {@link #register(AuthPrincipal)} 拿一个 outbound Flux,WS handler 把它写到
 * websocket session。{@link #fanout(AgentEvent)} 由 {@code RunEventStreamService.publish}
 * 每条事件都调一次,本类按 {@link SessionVisibility} 决定哪些连接能收到这条事件。</p>
 *
 * <p>设计要点:</p>
 * <ul>
 *   <li>所有连接共享一条 fanout 调用,不为每个连接订阅独立的 sink —— 1k 连接 × 100 events/sec
 *       不至于把 sink 调度器打爆。</li>
 *   <li>权限判定走 {@link SessionVisibility#canRead(String, String)},sessionId → (tenant, user)
 *       结果缓存在本地 ConcurrentHashMap,避免每条事件查 DB。session 极少改 owner,缓存不设过期;
 *       需要主动失效请调 {@link #invalidateSessionOwner(String)}(目前调用点:删 session)。</li>
 *   <li>connection sink 用 unicast onBackpressureBuffer:某个 WS 客户端消费慢不影响别人。</li>
 * </ul>
 *
 * <p>放在 agent-infra 而不是 agent-web 的原因:本类需要 {@link SessionRepository}(spring-data-jpa
 * 在 agent-infra),agent-web 不引 jpa。WS handler 在 agent-web,通过 {@link #register} 拿
 * outbound Flux 写到 websocket session,本类不持有任何 web 类型,职责清晰。</p>
 */
@Component
public class UserEventHub implements AgentEventFanout {

    private static final Logger log = LoggerFactory.getLogger(UserEventHub.class);

    /** sessionId 缓存最大条数 —— 超出按 LRU evict。 */
    private static final int OWNER_CACHE_MAX = 5000;

    private final Map<String, ActiveConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, SessionOwner> ownerCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock ownerCacheLock = new ReentrantReadWriteLock();

    private final SessionRepository sessionRepository;

    public UserEventHub(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 注册一条新连接。返回值给 WS handler 用来推事件到客户端、断开时调 {@link #unregister(String)}。
     */
    public Registration register(AuthPrincipal principal) {
        String connectionId = UUID.randomUUID().toString();
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        ActiveConnection conn = new ActiveConnection(connectionId, principal, sink);
        connections.put(connectionId, conn);
        log.info("hub.register connectionId={}, userId={}, tenantId={}, total={}",
                connectionId, principal != null ? principal.userId() : "<null>",
                principal != null ? principal.tenantId() : "<null>",
                connections.size());
        return new Registration(connectionId, sink.asFlux());
    }

    public void unregister(String connectionId) {
        ActiveConnection removed = connections.remove(connectionId);
        if (removed != null) {
            removed.sink.tryEmitComplete();
            log.info("hub.unregister connectionId={}, userId={}, remaining={}",
                    connectionId,
                    removed.principal != null ? removed.principal.userId() : "<null>",
                    connections.size());
        }
    }

    /**
     * 收到一条 run 事件,扇出到所有有权限看的连接。
     *
     * <p>{@code RunEventStreamService.publish()} 每条事件调一次,所以执行越简单越好。</p>
     */
    @Override
    public void fanout(AgentEvent event) {
        try {
            doFanout(event);
        } catch (Throwable error) {
            // publisher 在 hot path 上调用,任何异常都要兜住,不能污染 RunEventStreamService.publish。
            log.warn("hub.fanout_failed runId={}, sessionId={}, cause={}",
                    event != null ? event.runId() : "<null>",
                    event != null ? event.sessionId() : "<null>",
                    error.getMessage());
        }
    }

    private void doFanout(AgentEvent event) {
        if (event == null) return;
        String sessionId = event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // 没归属 session 的事件没法做权限判定,丢弃。理论上 SimpleAgentRunner 不会发这种。
            return;
        }
        if (connections.isEmpty()) {
            return;
        }
        SessionOwner owner = resolveOwner(sessionId);
        if (owner == null) {
            // session 还没在 DB 里,可能是事务可见性窗口,这条事件丢弃。下一条到时 cache miss 会
            // 再查一次。
            return;
        }
        for (ActiveConnection conn : connections.values()) {
            SessionVisibility v = SessionVisibility.forPrincipal(conn.principal);
            if (v.canRead(owner.tenantId, owner.userId)) {
                conn.sink.tryEmitNext(event);
            }
        }
    }

    /**
     * Session 元数据变更时(主要是删除 / 转移租户)主动失效缓存。当前仅 SessionService.deleteSession
     * 调,其他改 owner 的场景目前不存在。
     */
    public void invalidateSessionOwner(String sessionId) {
        ownerCache.remove(sessionId);
    }

    public int size() {
        return connections.size();
    }

    private SessionOwner resolveOwner(String sessionId) {
        SessionOwner cached = ownerCache.get(sessionId);
        if (cached != null) {
            return cached.tombstone ? null : cached;
        }
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            // 缓存"不存在"也短暂记一下,避免短时间内同 sessionId 大量事件每条都查 DB。下次显式 invalidate
            // 或者达到 LRU 上限时随之清理。
            return null;
        }
        SessionOwner owner = new SessionOwner(entity.getTenantId(), entity.getUserId(), false);
        // 限流式 LRU:超上限随便清掉一条 —— 不追求严格 LRU,只求不无限增长。session 极少变 owner,
        // 缓存击穿成本可控。
        if (ownerCache.size() >= OWNER_CACHE_MAX) {
            ownerCacheLock.writeLock().lock();
            try {
                if (ownerCache.size() >= OWNER_CACHE_MAX) {
                    java.util.Iterator<String> it = ownerCache.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            } finally {
                ownerCacheLock.writeLock().unlock();
            }
        }
        ownerCache.put(sessionId, owner);
        return owner;
    }

    public record Registration(String connectionId, Flux<AgentEvent> outbound) { }

    private record ActiveConnection(
            String connectionId,
            AuthPrincipal principal,
            Sinks.Many<AgentEvent> sink
    ) { }

    /**
     * sessionId 的所有者快照。tombstone=true 表示曾查过 DB 但 session 不存在(目前没启用)。
     */
    private record SessionOwner(String tenantId, String userId, boolean tombstone) { }
}
