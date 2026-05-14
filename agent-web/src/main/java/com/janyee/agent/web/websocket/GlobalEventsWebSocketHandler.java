package com.janyee.agent.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.JwtService;
import com.janyee.agent.infra.auth.PermissionResolver;
import com.janyee.agent.infra.fanout.UserEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 用户级 WebSocket 入口。每个登录客户端启动后建一条到 /ws/events 的长连接,服务端按权限把它有权
 * 看到的所有 session 的事件流推过来。前端只需要一个 dispatcher 处理所有事件,不再为每个 run / 每个
 * session 单独开 WS。
 *
 * <p>取代了原来的 /ws/chat:</p>
 * <ul>
 *   <li>启动 run 永远走 HTTP POST /api/chat/send,WS 不再发起执行</li>
 *   <li>WS URL 只需要 access_token(或 AGENT_TOKEN cookie),不再带 sessionId / runId / attach / message</li>
 *   <li>事件分发由 {@link UserEventHub} 集中做,跨 session、跨多管理员 fan-out 都在那一处实现</li>
 * </ul>
 *
 * <p>鉴权失败(无 token / token 过期 / 无 permission)直接关闭连接,不发任何事件。</p>
 */
@Component
public class GlobalEventsWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalEventsWebSocketHandler.class);

    private final UserEventHub hub;
    private final JwtService jwtService;
    private final PermissionResolver permissionResolver;
    private final ObjectMapper objectMapper;

    public GlobalEventsWebSocketHandler(
            UserEventHub hub,
            JwtService jwtService,
            PermissionResolver permissionResolver,
            ObjectMapper objectMapper
    ) {
        this.hub = hub;
        this.jwtService = jwtService;
        this.permissionResolver = permissionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        AuthPrincipal principal = resolvePrincipal(session);
        if (principal == null || principal.anonymous()) {
            log.info("ws.events.reject_unauthenticated remote={}", remoteAddr(session));
            ObjectNode err = objectMapper.createObjectNode();
            err.put("type", "AUTH_REJECTED");
            err.put("message", "missing or invalid access_token; reconnect after login");
            return session.send(Flux.just(session.textMessage(err.toString())));
        }
        UserEventHub.Registration registration = hub.register(principal);
        log.info("ws.events.open connectionId={}, userId={}, tenantId={}",
                registration.connectionId(), principal.userId(), principal.tenantId());
        Flux<WebSocketMessage> outbound = registration.outbound()
                .map(event -> toWebSocketMessage(session, event));
        return sendWithPingPong(session, outbound)
                .doFinally(sig -> hub.unregister(registration.connectionId()));
    }

    private AuthPrincipal resolvePrincipal(WebSocketSession session) {
        // 优先 access_token query 参数(浏览器 WebSocket API 不支持 Authorization header)。
        // 其次 AGENT_TOKEN cookie —— 同源同 origin 时浏览器自动带,免去前端拼 URL 的麻烦。
        String token = extractTokenFromQuery(session);
        if (token == null || token.isBlank()) {
            token = extractTokenFromCookie(session);
        }
        if (token == null || token.isBlank()) {
            return null;
        }
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(token);
        } catch (Exception error) {
            log.info("ws.events.token_parse_failed cause={}", error.getMessage());
            return null;
        }
        String userId = parsed.userId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String tenantId = parsed.tenantId() == null ? "system" : parsed.tenantId();
        String appId = parsed.appId() == null ? "system-default" : parsed.appId();
        Set<String> permissions;
        try {
            permissions = permissionResolver.resolve(userId, tenantId);
        } catch (Exception error) {
            log.warn("ws.events.perm_resolve_failed userId={}, cause={}", userId, error.getMessage());
            permissions = Set.of();
        }
        return new AuthPrincipal(userId, tenantId, appId, permissions, false);
    }

    private String extractTokenFromQuery(WebSocketSession session) {
        String raw = session.getHandshakeInfo().getUri().getRawQuery();
        if (raw == null || raw.isBlank()) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            if ("access_token".equals(key)) {
                String value = pair.substring(eq + 1);
                // URLDecoder 在 token 不含 % 时是 no-op,有 % / + 时正确解码
                try {
                    return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return value;
                }
            }
        }
        return null;
    }

    private String extractTokenFromCookie(WebSocketSession session) {
        MultiValueMap<String, HttpCookie> cookies = session.getHandshakeInfo().getCookies();
        if (cookies == null) return null;
        HttpCookie c = cookies.getFirst("AGENT_TOKEN");
        return c == null ? null : c.getValue();
    }

    private String remoteAddr(WebSocketSession session) {
        return session.getHandshakeInfo().getRemoteAddress() == null
                ? "?" : String.valueOf(session.getHandshakeInfo().getRemoteAddress());
    }

    private WebSocketMessage toWebSocketMessage(WebSocketSession session, AgentEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.type().name());
            payload.put("sessionId", event.sessionId());
            payload.put("runId", event.runId());
            payload.put("content", event.content());
            payload.put("timestamp", event.timestamp());
            // 不再像旧 ChatWebSocketHandler 那样从 binding.agentId() 拍一个 agentId 进去 ——
            // 这条 WS 不绑定特定 agent,事件 payload 里前端用 sessionId/runId 去查 agent。
            return session.textMessage(objectMapper.writeValueAsString(payload));
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize websocket event", error);
        }
    }

    /**
     * 跟旧 handler 同样的 ping/pong 模式:客户端发 {"type":"ping","id":N} 后端立刻回
     * {"type":"pong","id":N,"ts":...}。half-open 检测靠这个,浏览器 WebSocket API 不暴露原生 ping。
     */
    private Mono<Void> sendWithPingPong(WebSocketSession session, Flux<WebSocketMessage> outbound) {
        Sinks.Many<WebSocketMessage> pongSink = Sinks.many().unicast().onBackpressureBuffer();

        reactor.core.Disposable inboundSub = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(text -> {
                    if (text == null || text.isBlank() || text.charAt(0) != '{') return;
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(text);
                        if (!"ping".equalsIgnoreCase(node.path("type").asText(""))) return;
                        ObjectNode pong = objectMapper.createObjectNode();
                        pong.put("type", "pong");
                        pong.put("ts", System.currentTimeMillis());
                        if (node.has("id")) pong.set("id", node.get("id"));
                        pongSink.tryEmitNext(session.textMessage(pong.toString()));
                    } catch (Exception ignored) {
                        // 不是 JSON / 不是 ping → 忽略
                    }
                })
                .subscribe(
                        v -> { /* no-op */ },
                        err -> pongSink.tryEmitComplete(),
                        () -> pongSink.tryEmitComplete()
                );

        Flux<WebSocketMessage> merged = Flux.merge(
                outbound.doFinally(sig -> {
                    pongSink.tryEmitComplete();
                    inboundSub.dispose();
                }),
                pongSink.asFlux()
        );
        return session.send(merged);
    }
}
