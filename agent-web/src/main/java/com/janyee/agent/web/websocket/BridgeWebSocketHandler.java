package com.janyee.agent.web.websocket;

import com.janyee.agent.runtime.bridge.ExternalBridgeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Component
public class BridgeWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);

    private final ExternalBridgeGateway externalBridgeGateway;

    public BridgeWebSocketHandler(ExternalBridgeGateway externalBridgeGateway) {
        this.externalBridgeGateway = externalBridgeGateway;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Map<String, String> params = QueryParamSupport.parse(session.getHandshakeInfo().getUri().getRawQuery());
        String sessionId = params.getOrDefault("sessionId", UUID.randomUUID().toString());
        log.info("ws.bridge.open sessionId={}", sessionId);

        Mono<Void> outbound = session.send(
                externalBridgeGateway.openSession(sessionId)
                        .map(session::textMessage)
        );

        Mono<Void> inbound = session.receive()
                .map(message -> message.getPayloadAsText())
                .doOnNext(payload -> externalBridgeGateway.onClientMessage(sessionId, payload))
                .then();

        return Mono.when(outbound, inbound)
                .doFinally(signal -> {
                    externalBridgeGateway.closeSession(sessionId);
                    log.info("ws.bridge.close sessionId={}, signal={}", sessionId, signal);
                });
    }
}

