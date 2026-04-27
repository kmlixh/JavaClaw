package com.janyee.agent.web.controller;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import com.janyee.agent.runtime.chat.PendingRunLaunch;
import com.janyee.agent.runtime.chat.PendingRunLaunchStore;
import com.janyee.agent.runtime.run.RunRecordService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentRunner agentRunner;
    private final RunRecordService runRecordService;
    private final AgentRouter agentRouter;
    private final LlmConfigService llmConfigService;
    private final PendingRunLaunchStore pendingRunLaunchStore;

    public ChatController(
            AgentRunner agentRunner,
            RunRecordService runRecordService,
            AgentRouter agentRouter,
            LlmConfigService llmConfigService,
            PendingRunLaunchStore pendingRunLaunchStore
    ) {
        this.agentRunner = agentRunner;
        this.runRecordService = runRecordService;
        this.agentRouter = agentRouter;
        this.llmConfigService = llmConfigService;
        this.pendingRunLaunchStore = pendingRunLaunchStore;
    }

    @PostMapping("/send")
    public ChatSendResponse send(@Valid @RequestBody ChatSendRequest request) {
        log.info("chat.send.received sessionId={}, userId={}, requestedAgentId={}, llmConfigId={}, messageLength={}",
                request.sessionId(), request.userId(), request.agentId(), request.llmConfigId(),
                request.message() != null ? request.message().length() : 0);
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        String userId = request.userId() != null && !request.userId().isBlank() ? request.userId() : "anonymous";
        AgentBinding binding = agentRouter.route(new AgentRouteRequest("web", userId, sessionId, request.agentId()));
        String agentId = binding.agentId();
        LlmConfigDescriptor llmConfig = applyModelOverride(
                llmConfigService.resolveRequested(request.llmConfigId()).orElse(null),
                request.llmModel()
        );
        if (llmConfig == null) {
            log.warn("chat.send.rejected sessionId={}, userId={}, requestedAgentId={}, reason=no_available_llm",
                    sessionId, userId, request.agentId());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No available LLM configuration. Start the backend with the correct profile and load llm_provider_config first."
            );
        }
        String runId = runRecordService.createAcceptedRun(
                sessionId,
                agentId,
                userId,
                request.message(),
                toDomainReferences(request),
                toDomainAttachments(request),
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.provider() : null,
                llmConfig != null ? llmConfig.model() : null
        );
        java.util.List<ChatContextReference> references = toDomainReferences(request);
        java.util.List<ChatAttachment> attachments = toDomainAttachments(request);
        pendingRunLaunchStore.save(new PendingRunLaunch(
                runId,
                sessionId,
                agentId,
                userId,
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.model() : null,
                request.message(),
                references,
                attachments
        ));
        log.info("chat.send.accepted sessionId={}, runId={}, agentId={}, llmConfigId={}, model={}",
                sessionId, runId, agentId,
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.model() : null);
        return new ChatSendResponse(
                sessionId,
                agentId,
                runId,
                "accepted",
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.model() : null
        );
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "llmConfigId", required = false) String llmConfigId,
            @RequestParam(value = "llmModel", required = false) String llmModel,
            @RequestParam(value = "userId", required = false, defaultValue = "anonymous") String userId,
            @RequestParam("message") String message
    ) {
        log.info("chat.stream.open requestedRunId={}, sessionId={}, userId={}, requestedAgentId={}, llmConfigId={}, messageLength={}",
                runId, sessionId, userId, agentId, llmConfigId, message != null ? message.length() : 0);
        AgentBinding binding = agentRouter.route(new AgentRouteRequest("web", userId, sessionId, agentId));
        String resolvedAgentId = binding.agentId();
        LlmConfigDescriptor llmConfig = applyModelOverride(
                llmConfigService.resolveRequested(llmConfigId).orElse(null),
                llmModel
        );
        if (llmConfig == null) {
            log.warn("chat.stream.rejected sessionId={}, userId={}, requestedAgentId={}, reason=no_available_llm",
                    sessionId, userId, agentId);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No available LLM configuration. Start the backend with the correct profile and load llm_provider_config first."
            );
        }
        PendingRunLaunch pending = runId != null && !runId.isBlank()
                ? pendingRunLaunchStore.consume(runId).orElse(null)
                : null;
        if (pending != null) {
            llmConfig = applyModelOverride(
                    llmConfigService.resolveRequested(pending.llmConfigId()).orElse(llmConfig),
                    pending.llmModel()
            );
        }
        String effectiveMessage = pending != null ? pending.message() : message;
        String effectiveRunId = runId != null && !runId.isBlank()
                ? runId
                : runRecordService.createAcceptedRun(
                        sessionId,
                        resolvedAgentId,
                        userId,
                        effectiveMessage,
                        pending != null ? pending.references() : java.util.List.of(),
                        pending != null ? pending.attachments() : java.util.List.of(),
                        llmConfig != null ? llmConfig.configId() : null,
                        llmConfig != null ? llmConfig.provider() : null,
                        llmConfig != null ? llmConfig.model() : null
                );
        log.info("chat.stream.start runId={}, sessionId={}, agentId={}, llmConfigId={}, model={}",
                effectiveRunId, sessionId, resolvedAgentId,
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.model() : null);
        RunRequest runRequest = new RunRequest(
                effectiveRunId,
                sessionId,
                resolvedAgentId,
                userId,
                effectiveMessage,
                false,
                llmConfig != null ? llmConfig.configId() : null,
                llmConfig != null ? llmConfig.model() : null,
                pending != null ? pending.references() : java.util.List.of(),
                pending != null ? pending.attachments() : java.util.List.of()
        );
        return agentRunner.run(runRequest)
                .doOnNext(event -> log.debug("chat.stream.event runId={}, type={}, content={}",
                        event.runId(), event.type(), event.content()))
                .doOnComplete(() -> log.info("chat.stream.complete runId={}, sessionId={}", effectiveRunId, sessionId))
                .doOnError(error -> log.error("chat.stream.error runId={}, sessionId={}", effectiveRunId, sessionId, error))
                .map(event -> ServerSentEvent.<String>builder(event.content())
                        .event(event.type().name())
                        .id(event.runId())
                        .build());
    }

    private java.util.List<ChatContextReference> toDomainReferences(ChatSendRequest request) {
        if (request.references() == null) {
            return java.util.List.of();
        }
        return request.references().stream()
                .filter(item -> item != null && item.id() != null && item.type() != null)
                .map(item -> new ChatContextReference(item.type(), item.id(), item.label()))
                .toList();
    }

    private java.util.List<ChatAttachment> toDomainAttachments(ChatSendRequest request) {
        if (request.attachments() == null) {
            return java.util.List.of();
        }
        return request.attachments().stream()
                .filter(item -> item != null && item.name() != null && item.content() != null)
                .map(item -> new ChatAttachment(item.name(), item.contentType(), item.content()))
                .toList();
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
