package com.janyee.agent.web.controller;

import com.janyee.agent.api.AgentDefinitionResponse;
import com.janyee.agent.api.ArtifactResponse;
import com.janyee.agent.api.LlmConfigResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.SearchResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.api.SessionMessageResponse;
import com.janyee.agent.api.SessionSummaryResponse;
import com.janyee.agent.api.SessionTitleUpdateRequest;
import com.janyee.agent.api.MemoryNoteResponse;
import com.janyee.agent.api.ToolAuditLogResponse;
import com.janyee.agent.runtime.artifact.ArtifactBinary;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.SessionDetailView;
import com.janyee.agent.runtime.session.SessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final AgentQueryService agentQueryService;
    private final AgentDefinitionService agentDefinitionService;
    private final LlmConfigService llmConfigService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;

    public QueryController(
            AgentQueryService agentQueryService,
            AgentDefinitionService agentDefinitionService,
            LlmConfigService llmConfigService,
            SessionService sessionService,
            ArtifactService artifactService
    ) {
        this.agentQueryService = agentQueryService;
        this.agentDefinitionService = agentDefinitionService;
        this.llmConfigService = llmConfigService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
    }

    @GetMapping("/agents")
    public java.util.List<AgentDefinitionResponse> listAgents() {
        return agentDefinitionService.listAgents().stream()
                .map(agent -> new AgentDefinitionResponse(
                        agent.id(),
                        agent.displayName(),
                        agent.workspacePath()
                ))
                .toList();
    }

    @GetMapping("/llms")
    public java.util.List<LlmConfigResponse> listLlms() {
        return llmConfigService.listAvailable().stream()
                .map(config -> new LlmConfigResponse(
                        config.configId(),
                        config.provider(),
                        config.displayName(),
                        config.model(),
                        config.stream(),
                        config.defaultConfig()
                ))
                .toList();
    }

    @GetMapping("/sessions")
    public java.util.List<SessionSummaryResponse> listSessions(
            @RequestParam(value = "agentId", required = false) String agentId
    ) {
        return agentQueryService.listSessions(agentId).stream()
                .map(session -> new SessionSummaryResponse(
                        session.sessionId(),
                        session.title(),
                        session.agentId(),
                        session.userId(),
                        session.channel(),
                        session.status(),
                        session.createdAt(),
                        session.updatedAt()
                ))
                .toList();
    }

    @GetMapping("/sessions/{id}")
    public SessionDetailResponse getSession(@PathVariable("id") String id) {
        SessionDetailView view = agentQueryService.getSession(id);
        return new SessionDetailResponse(
                view.sessionId(),
                view.title(),
                view.agentId(),
                view.userId(),
                view.channel(),
                view.status(),
                view.createdAt(),
                view.updatedAt(),
                view.messages().stream()
                        .map(message -> new SessionMessageResponse(
                                message.id(),
                                message.runId(),
                                message.role(),
                                message.messageType(),
                                message.content(),
                                message.toolName(),
                                message.toolArgsJson(),
                                message.toolResultJson(),
                                message.seqNo(),
                                message.createdAt()
                        ))
                        .toList()
        );
    }

    @PostMapping("/sessions/{id}/title")
    public SessionDetailResponse updateSessionTitle(
            @PathVariable("id") String id,
            @Valid @RequestBody SessionTitleUpdateRequest request
    ) {
        sessionService.renameSession(id, request.title());
        return getSession(id);
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable("id") String id) {
        sessionService.deleteSession(id);
    }

    @GetMapping("/runs/{id}")
    public RunDetailResponse getRun(@PathVariable("id") String id) {
        RunDetailView view = agentQueryService.getRun(id);
        return new RunDetailResponse(
                view.runId(),
                view.sessionId(),
                view.agentId(),
                view.userId(),
                view.llmConfigId(),
                view.llmProvider(),
                view.llmModel(),
                view.status(),
                view.detail(),
                view.createdAt(),
                view.updatedAt(),
                view.toolAudits().stream()
                        .map(audit -> new ToolAuditLogResponse(
                                audit.id(),
                                audit.requestId(),
                                audit.toolName(),
                                audit.phase(),
                                audit.allowed(),
                                audit.approvalRequired(),
                                audit.success(),
                                audit.executed(),
                                audit.reason(),
                                audit.argumentsJson(),
                                audit.resultSummary(),
                                audit.errorMessage(),
                                audit.durationMillis(),
                                audit.createdAt()
                        ))
                        .toList(),
                view.artifacts().stream()
                        .map(artifact -> new ArtifactResponse(
                                artifact.id(),
                                artifact.sessionId(),
                                artifact.runId(),
                                artifact.agentId(),
                                artifact.artifactType(),
                                artifact.name(),
                                artifact.path(),
                                artifact.contentType(),
                                artifact.sizeBytes(),
                                artifact.createdAt()
                        ))
                        .toList()
        );
    }

    @GetMapping("/agents/{id}/memories")
    public java.util.List<MemoryNoteResponse> listMemories(@PathVariable("id") String id) {
        return agentQueryService.listMemoryNotes(id).stream()
                .map(note -> new MemoryNoteResponse(
                        note.id(),
                        note.agentId(),
                        note.sessionId(),
                        note.runId(),
                        note.source(),
                        note.content(),
                        note.createdAt()
                ))
                .toList();
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "runId", required = false) String runId
    ) {
        return new SearchResponse(
                agentQueryService.searchMessages(query, sessionId).stream()
                        .map(message -> new SessionMessageResponse(
                                message.id(),
                                message.runId(),
                                message.role(),
                                message.messageType(),
                                message.content(),
                                message.toolName(),
                                message.toolArgsJson(),
                                message.toolResultJson(),
                                message.seqNo(),
                                message.createdAt()
                        ))
                        .toList(),
                agentId == null || agentId.isBlank()
                        ? java.util.List.of()
                        : agentQueryService.searchMemoryNotes(agentId, query).stream()
                        .map(note -> new MemoryNoteResponse(
                                note.id(),
                                note.agentId(),
                                note.sessionId(),
                                note.runId(),
                                note.source(),
                                note.content(),
                                note.createdAt()
                        ))
                        .toList(),
                runId == null || runId.isBlank()
                        ? java.util.List.of()
                        : agentQueryService.searchArtifacts(runId, query).stream()
                        .map(artifact -> new ArtifactResponse(
                                artifact.id(),
                                artifact.sessionId(),
                                artifact.runId(),
                                artifact.agentId(),
                                artifact.artifactType(),
                                artifact.name(),
                                artifact.path(),
                                artifact.contentType(),
                                artifact.sizeBytes(),
                                artifact.createdAt()
                        ))
                        .toList()
        );
    }

    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<ByteArrayResource> downloadArtifact(@PathVariable("id") Long id) {
        ArtifactBinary artifact = artifactService.loadArtifact(id);
        String contentType = artifact.artifact().contentType();
        MediaType mediaType = contentType != null && !contentType.isBlank()
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.artifact().name() + "\"")
                .contentLength(artifact.content().length)
                .body(new ByteArrayResource(artifact.content()));
    }
}
