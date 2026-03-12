package com.janyee.agent.web.controller;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import com.janyee.agent.runtime.run.RunRecordService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentRunner agentRunner;
    private final RunRecordService runRecordService;
    private final AgentRouter agentRouter;

    public ChatController(AgentRunner agentRunner, RunRecordService runRecordService, AgentRouter agentRouter) {
        this.agentRunner = agentRunner;
        this.runRecordService = runRecordService;
        this.agentRouter = agentRouter;
    }

    @PostMapping("/send")
    public ChatSendResponse send(@Valid @RequestBody ChatSendRequest request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        String userId = request.userId() != null && !request.userId().isBlank() ? request.userId() : "anonymous";
        AgentBinding binding = agentRouter.route(new AgentRouteRequest("web", userId, sessionId, request.agentId()));
        String agentId = binding.agentId();
        String runId = runRecordService.createAcceptedRun(sessionId, agentId, userId, request.message());
        return new ChatSendResponse(sessionId, agentId, runId, "accepted");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "userId", required = false, defaultValue = "anonymous") String userId,
            @RequestParam("message") String message
    ) {
        AgentBinding binding = agentRouter.route(new AgentRouteRequest("web", userId, sessionId, agentId));
        String resolvedAgentId = binding.agentId();
        String effectiveRunId = runId != null && !runId.isBlank()
                ? runId
                : runRecordService.createAcceptedRun(sessionId, resolvedAgentId, userId, message);
        RunRequest runRequest = new RunRequest(effectiveRunId, sessionId, resolvedAgentId, userId, message, false);
        return agentRunner.run(runRequest)
                .map(event -> ServerSentEvent.<String>builder(event.content())
                        .event(event.type().name())
                        .id(event.runId())
                        .build());
    }
}
