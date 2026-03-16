package com.janyee.agent.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import com.janyee.agent.runtime.chat.PendingRunLaunch;
import com.janyee.agent.runtime.chat.PendingRunLaunchStore;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.run.RunRecordService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private final AgentRunner agentRunner;
    private final RunRecordService runRecordService;
    private final AgentRouter agentRouter;
    private final LlmConfigService llmConfigService;
    private final ObjectMapper objectMapper;
    private final PendingRunLaunchStore pendingRunLaunchStore;

    public ChatWebSocketHandler(
            AgentRunner agentRunner,
            RunRecordService runRecordService,
            AgentRouter agentRouter,
            LlmConfigService llmConfigService,
            ObjectMapper objectMapper,
            PendingRunLaunchStore pendingRunLaunchStore
    ) {
        this.agentRunner = agentRunner;
        this.runRecordService = runRecordService;
        this.agentRouter = agentRouter;
        this.llmConfigService = llmConfigService;
        this.objectMapper = objectMapper;
        this.pendingRunLaunchStore = pendingRunLaunchStore;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Map<String, String> params = QueryParamSupport.parse(session.getHandshakeInfo().getUri().getRawQuery());
        String sessionId = valueOrDefault(params.get("sessionId"), UUID.randomUUID().toString());
        String userId = valueOrDefault(params.get("userId"), "anonymous");
        String message = valueOrDefault(params.get("message"), "hello");
        String requestedAgentId = params.get("agentId");
        String requestedLlmConfigId = params.get("llmConfigId");
        String runId = params.get("runId");

        AgentBinding binding = agentRouter.route(new AgentRouteRequest("websocket", userId, sessionId, requestedAgentId));
        LlmConfigDescriptor llmConfig = llmConfigService.resolveRequested(requestedLlmConfigId).orElse(null);
        PendingRunLaunch pending = runId != null && !runId.isBlank()
                ? pendingRunLaunchStore.consume(runId).orElse(null)
                : null;
        String effectiveMessage = pending != null ? pending.message() : message;
        String resolvedRunId = runId != null && !runId.isBlank()
                ? runId
                : runRecordService.createAcceptedRun(
                        sessionId,
                        binding.agentId(),
                        userId,
                        effectiveMessage,
                        llmConfig != null ? llmConfig.configId() : null,
                        llmConfig != null ? llmConfig.provider() : null,
                        llmConfig != null ? llmConfig.model() : null
                );
        RunRequest request = new RunRequest(
                resolvedRunId,
                sessionId,
                binding.agentId(),
                userId,
                effectiveMessage,
                false,
                llmConfig != null ? llmConfig.configId() : null,
                pending != null ? pending.references() : java.util.List.of(),
                pending != null ? pending.attachments() : java.util.List.of()
        );

        return session.send(agentRunner.run(request)
                .map(event -> {
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("type", event.type().name());
                        payload.put("sessionId", event.sessionId());
                        payload.put("runId", event.runId());
                        payload.put("content", event.content());
                        payload.put("timestamp", event.timestamp());
                        payload.put("agentId", binding.agentId());
                        return session.textMessage(objectMapper.writeValueAsString(payload));
                    } catch (Exception error) {
                        throw new IllegalStateException("failed to serialize websocket event", error);
                    }
                }));
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
