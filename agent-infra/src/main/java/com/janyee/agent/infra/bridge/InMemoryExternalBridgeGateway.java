package com.janyee.agent.infra.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.bridge.ExternalBridgeGateway;
import com.janyee.agent.runtime.bridge.ExternalBridgeResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class InMemoryExternalBridgeGateway implements ExternalBridgeGateway {

    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;
    private static final long MAX_TIMEOUT_MILLIS = 120_000L;

    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<String>> sessionOutboxes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ExternalBridgeResult>> pendingInvocations = new ConcurrentHashMap<>();

    public InMemoryExternalBridgeGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<String> openSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Flux.empty();
        }
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessionOutboxes.put(sessionId, sink);
        return sink.asFlux()
                .doFinally(signal -> closeSession(sessionId));
    }

    @Override
    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Sinks.Many<String> sink = sessionOutboxes.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    @Override
    public void onClientMessage(String sessionId, String messageJson) {
        if (sessionId == null || sessionId.isBlank() || messageJson == null || messageJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(messageJson);
            String type = root.path("type").asText("");
            if (!"invoke_result".equals(type)) {
                return;
            }
            String requestId = root.path("requestId").asText("");
            if (requestId.isBlank()) {
                return;
            }
            CompletableFuture<ExternalBridgeResult> pending = pendingInvocations.remove(requestId);
            if (pending == null) {
                return;
            }
            boolean success = root.path("success").asBoolean(false);
            String resultJson = normalizeResultJson(root.path("result"));
            String error = root.path("error").asText(null);
            String summary = success ? "host method executed" : "host method failed";
            pending.complete(new ExternalBridgeResult(success, summary, resultJson, error));
        } catch (Exception ignored) {
            // Ignore malformed bridge client messages.
        }
    }

    @Override
    public ExternalBridgeResult invoke(String sessionId, String runId, String method, String payloadJson, long timeoutMs) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ExternalBridgeResult(false, "bridge session missing", "{}", "sessionId is required");
        }
        if (method == null || method.isBlank()) {
            return new ExternalBridgeResult(false, "bridge method missing", "{}", "method is required");
        }
        Sinks.Many<String> outbox = sessionOutboxes.get(sessionId);
        if (outbox == null) {
            return new ExternalBridgeResult(false, "bridge client not connected", "{}", "no bridge websocket client for session");
        }
        long effectiveTimeout = normalizeTimeout(timeoutMs);
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ExternalBridgeResult> future = new CompletableFuture<>();
        pendingInvocations.put(requestId, future);
        try {
            String invokePayload = objectMapper.writeValueAsString(Map.of(
                    "type", "invoke",
                    "requestId", requestId,
                    "runId", runId == null ? "" : runId,
                    "method", method,
                    "payload", parsePayload(payloadJson)
            ));
            Sinks.EmitResult emitted = outbox.tryEmitNext(invokePayload);
            if (emitted.isFailure()) {
                pendingInvocations.remove(requestId);
                return new ExternalBridgeResult(false, "bridge delivery failed", "{}", "failed to deliver invoke request: " + emitted);
            }
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            pendingInvocations.remove(requestId);
            return new ExternalBridgeResult(false, "bridge timeout", "{}", "host did not return in %d ms".formatted(effectiveTimeout));
        } catch (Exception error) {
            pendingInvocations.remove(requestId);
            return new ExternalBridgeResult(false, "bridge invoke failed", "{}", error.getMessage());
        }
    }

    private long normalizeTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return Math.min(timeoutMs, MAX_TIMEOUT_MILLIS);
    }

    private JsonNode parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String normalizeResultJson(JsonNode node) {
        try {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            return "{}";
        }
    }
}
