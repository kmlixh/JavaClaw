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
import com.janyee.agent.runtime.run.RunRecordService;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 唯一的对外发消息入口。POST /api/chat/send。
 *
 * <p>新通信架构里这条接口只负责"接受新消息 / 起 run / 返回 runId",事件流不再从 HTTP 响应回。
 * 实时事件全部通过 /ws/events 总线推到客户端,由 {@link com.janyee.agent.infra.fanout.UserEventHub}
 * 按权限 fan-out。</p>
 *
 * <p>所以本类比之前简单很多:</p>
 * <ul>
 *   <li>不再有 GET /stream(SSE)路径 —— 老 EventSource 不再支持</li>
 *   <li>不再保存 PendingRunLaunch —— 没有 WS attach 路径需要消费它</li>
 *   <li>same-session 已经在跑的情况返回那个 run 的 runId,但 status 仍然返回 "accepted"
 *       (从前端视角看,这两种情况都是"消息进了系统,事件会通过 /ws/events 推过来")</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentRunner agentRunner;
    private final RunRecordService runRecordService;
    private final AgentRouter agentRouter;
    private final LlmConfigService llmConfigService;
    private final AgentQueryService agentQueryService;

    public ChatController(
            AgentRunner agentRunner,
            RunRecordService runRecordService,
            AgentRouter agentRouter,
            LlmConfigService llmConfigService,
            AgentQueryService agentQueryService
    ) {
        this.agentRunner = agentRunner;
        this.runRecordService = runRecordService;
        this.agentRouter = agentRouter;
        this.llmConfigService = llmConfigService;
        this.agentQueryService = agentQueryService;
    }

    @PostMapping("/send")
    public ChatSendResponse send(
            @Valid @RequestBody ChatSendRequest request,
            org.springframework.web.server.ServerWebExchange exchange
    ) {
        log.info("chat.send.received sessionId={}, userId={}, requestedAgentId={}, llmConfigId={}, messageLength={}",
                request.sessionId(), request.userId(), request.agentId(), request.llmConfigId(),
                request.message() != null ? request.message().length() : 0);
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                (com.janyee.agent.infra.auth.AuthPrincipal) exchange.getAttributes()
                        .get(com.janyee.agent.web.auth.JwtAuthWebFilter.PRINCIPAL_ATTR);
        if (principal != null) {
            com.janyee.agent.infra.auth.SecurityContextHolder.setCurrent(principal);
        }
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        String userId = principal != null && !principal.anonymous()
                && principal.userId() != null && !principal.userId().isBlank()
                ? principal.userId()
                : (request.userId() != null && !request.userId().isBlank() ? request.userId() : "anonymous");
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
        // 同 session 已经有 run 在跑 → 返回那个 run 的 runId,前端的 /ws/events 总线会自然推它的
        // 实时事件。这样多个 admin / 同一用户的多个 tab 想看同一 session 的进度都行,共视零成本。
        // 用户这次新发的 message 在 active run 存在时不会被消费,前端责任是保留到 composer 里、
        // 等当前 run 完成后让用户重发。
        java.util.Optional<RunDetailView> activeRun = agentQueryService.findActiveRun(sessionId);
        if (activeRun.isPresent()) {
            RunDetailView active = activeRun.get();
            log.info("chat.send.attach_active_run sessionId={}, runId={}, activeStatus={}",
                    sessionId, active.runId(), active.status());
            return new ChatSendResponse(
                    sessionId,
                    active.agentId(),
                    active.runId(),
                    "already_running",
                    active.llmConfigId(),
                    active.llmModel()
            );
        }
        String runId = runRecordService.createAcceptedRun(
                sessionId,
                agentId,
                userId,
                request.message(),
                toDomainReferences(request),
                toDomainAttachments(request),
                llmConfig.configId(),
                llmConfig.provider(),
                llmConfig.model()
        );
        java.util.List<ChatContextReference> references = toDomainReferences(request);
        java.util.List<ChatAttachment> attachments = toDomainAttachments(request);
        RunRequest runRequest = new RunRequest(
                runId,
                sessionId,
                agentId,
                userId,
                request.message(),
                false,
                llmConfig.configId(),
                llmConfig.model(),
                references,
                attachments,
                principal != null ? principal.userId() : null,
                principal != null ? principal.tenantId() : null,
                principal != null ? principal.appId() : null
        );
        // fire-and-forget。executeRun 在 boundedElastic 上独立跑,客户端断不断都不影响。
        // events 同步发布到 RunEventStreamService 然后被 UserEventHub fan-out 到所有有权
        // 看到这个 session 的 /ws/events 连接。
        agentRunner.run(runRequest).subscribe(
                event -> { /* events go to RunEventStreamService → UserEventHub */ },
                error -> log.error("chat.send.async_error runId={}, sessionId={}", runId, sessionId, error),
                () -> log.info("chat.send.async_complete runId={}, sessionId={}", runId, sessionId)
        );
        log.info("chat.send.accepted sessionId={}, runId={}, agentId={}, llmConfigId={}, model={}",
                sessionId, runId, agentId, llmConfig.configId(), llmConfig.model());
        return new ChatSendResponse(
                sessionId,
                agentId,
                runId,
                "accepted",
                llmConfig.configId(),
                llmConfig.model()
        );
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
                .filter(item -> item != null && item.name() != null
                        // 内联 base64 OR 已上传到工作区的 path,二选一即可
                        && (item.content() != null || (item.path() != null && !item.path().isBlank())))
                .map(item -> new ChatAttachment(
                        item.name(),
                        item.contentType(),
                        item.content(),
                        item.path(),
                        item.sizeBytes()))
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
