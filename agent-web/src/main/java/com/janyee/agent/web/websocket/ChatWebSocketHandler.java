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
import com.janyee.agent.runtime.run.RunEventStreamService;
import com.janyee.agent.runtime.run.RunRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)\"(password|apiKey|api_key|token|accessToken|access_token)\"\\s*:\\s*\"([^\"]*)\""
    );
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)(password|apiKey|api_key|token|accessToken|access_token)\\s*=\\s*([^,\\s\\]}]+)"
    );

    private final AgentRunner agentRunner;
    private final RunRecordService runRecordService;
    private final AgentRouter agentRouter;
    private final LlmConfigService llmConfigService;
    private final RunEventStreamService runEventStreamService;
    private final ObjectMapper objectMapper;
    private final PendingRunLaunchStore pendingRunLaunchStore;

    public ChatWebSocketHandler(
            AgentRunner agentRunner,
            RunRecordService runRecordService,
            AgentRouter agentRouter,
            LlmConfigService llmConfigService,
            RunEventStreamService runEventStreamService,
            ObjectMapper objectMapper,
            PendingRunLaunchStore pendingRunLaunchStore
    ) {
        this.agentRunner = agentRunner;
        this.runRecordService = runRecordService;
        this.agentRouter = agentRouter;
        this.llmConfigService = llmConfigService;
        this.runEventStreamService = runEventStreamService;
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
        String requestedLlmModel = params.get("llmModel");
        String runId = params.get("runId");
        boolean attachOnly = "true".equalsIgnoreCase(params.get("attach"));

        if (attachOnly && runId != null && !runId.isBlank()) {
            log.info("ws.chat.attach sessionId={}, runId={}", sessionId, runId);
            return session.send(runEventStreamService.replayAndSubscribe(runId)
                    .map(event -> toWebSocketMessage(session, event, params.get("agentId"))));
        }

        AgentBinding binding = agentRouter.route(new AgentRouteRequest("websocket", userId, sessionId, requestedAgentId));
        LlmConfigDescriptor llmConfig = applyModelOverride(
                llmConfigService.resolveRequested(requestedLlmConfigId).orElse(null),
                requestedLlmModel
        );
        PendingRunLaunch pending = runId != null && !runId.isBlank()
                ? pendingRunLaunchStore.consume(runId).orElse(null)
                : null;
        if (runId != null && !runId.isBlank() && pending == null) {
            log.info("ws.chat.attach_existing sessionId={}, runId={}", sessionId, runId);
            return session.send(runEventStreamService.replayAndSubscribe(runId)
                    .map(event -> toWebSocketMessage(session, event, binding.agentId())));
        }
        if (pending != null) {
            llmConfig = applyModelOverride(
                    llmConfigService.resolveRequested(pending.llmConfigId()).orElse(llmConfig),
                    pending.llmModel()
            );
        }
        String effectiveMessage = pending != null ? pending.message() : message;
        String resolvedRunId = runId != null && !runId.isBlank()
                ? runId
                : runRecordService.createAcceptedRun(
                        sessionId,
                        binding.agentId(),
                        userId,
                        effectiveMessage,
                        pending != null ? pending.references() : java.util.List.of(),
                        pending != null ? pending.attachments() : java.util.List.of(),
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
                llmConfig != null ? llmConfig.model() : null,
                pending != null ? pending.references() : java.util.List.of(),
                pending != null ? pending.attachments() : java.util.List.of()
        );

        return session.send(agentRunner.run(request)
                .map(event -> toWebSocketMessage(session, event, binding.agentId())));
    }

    private org.springframework.web.reactive.socket.WebSocketMessage toWebSocketMessage(
            WebSocketSession session,
            com.janyee.agent.domain.AgentEvent event,
            String agentId
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.type().name());
            payload.put("sessionId", event.sessionId());
            payload.put("runId", event.runId());
            payload.put("content", event.content());
            payload.put("timestamp", event.timestamp());
            payload.put("agentId", agentId);
            String phase = detectPhase(event.type().name(), event.content());
            if (!phase.isBlank()) {
                payload.put("phase", phase);
            }
            log.info("ws.chat.event sessionId={}, runId={}, type={}, content={}",
                    event.sessionId(),
                    event.runId(),
                    event.type(),
                    redactSecrets(event.content()));
            return session.textMessage(objectMapper.writeValueAsString(payload));
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize websocket event", error);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JSON_SECRET_PATTERN.matcher(value)
                .replaceAll("\"$1\":\"***\"");
        return KV_SECRET_PATTERN.matcher(redacted)
                .replaceAll("$1=***");
    }

    private String detectPhase(String eventType, String content) {
        if ("RUN_STATUS".equals(eventType)) {
            String text = content == null ? "" : content.trim();
            int phaseIndex = text.indexOf("phase=");
            if (phaseIndex >= 0) {
                int begin = phaseIndex + "phase=".length();
                int end = text.indexOf(' ', begin);
                return end > begin ? text.substring(begin, end) : text.substring(begin);
            }
            return "THINKING";
        }
        if ("TOOL_STARTED".equals(eventType) || "TOOL_REQUESTED".equals(eventType) || "TOOL_COMPLETED".equals(eventType)) {
            String text = content == null ? "" : content.toLowerCase();
            if (text.contains("db.query")) {
                return "QUERYING";
            }
            return "TOOL";
        }
        if ("RUN_COMPLETED".equals(eventType)) {
            return "COMPLETED";
        }
        if ("RUN_FAILED".equals(eventType)) {
            return "FAILED";
        }
        return "";
    }

    private LlmConfigDescriptor applyModelOverride(LlmConfigDescriptor config, String requestedModel) {
        if (config == null || requestedModel == null || requestedModel.isBlank()) {
            return config;
        }
        String model = requestedModel.trim();
        return new LlmConfigDescriptor(
                config.configId(),
                config.provider(),
                config.displayName(),
                model,
                config.modelMappingJson(),
                config.baseUrl(),
                config.apiKey(),
                config.chatPath(),
                config.stream(),
                config.enabled(),
                config.defaultConfig()
        );
    }
}
