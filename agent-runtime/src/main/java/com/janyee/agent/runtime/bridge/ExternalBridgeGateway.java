package com.janyee.agent.runtime.bridge;

import reactor.core.publisher.Flux;

public interface ExternalBridgeGateway {

    Flux<String> openSession(String sessionId);

    void closeSession(String sessionId);

    void onClientMessage(String sessionId, String messageJson);

    ExternalBridgeResult invoke(String sessionId, String runId, String method, String payloadJson, long timeoutMs);
}

