package com.janyee.agent.web.controller;

import com.janyee.agent.api.AgentDefinitionResponse;
import com.janyee.agent.api.ArtifactResponse;
import com.janyee.agent.api.CancelRunResponse;
import com.janyee.agent.api.LlmConfigResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.RunSummaryResponse;
import com.janyee.agent.api.SearchResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.api.SessionMessageResponse;
import com.janyee.agent.api.SessionSummaryResponse;
import com.janyee.agent.api.SessionTitleUpdateRequest;
import com.janyee.agent.api.MemoryNoteResponse;
import com.janyee.agent.api.ToolAuditLogResponse;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.runtime.artifact.ArtifactBinary;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.SessionDetailView;
import com.janyee.agent.runtime.run.RunCancellationRegistry;
import com.janyee.agent.runtime.run.RunRecordService;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.infra.auth.PermissionGate;
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
    private final RunCancellationRegistry cancellationRegistry;
    private final RunRecordService runRecordService;

    public QueryController(
            AgentQueryService agentQueryService,
            AgentDefinitionService agentDefinitionService,
            LlmConfigService llmConfigService,
            SessionService sessionService,
            ArtifactService artifactService,
            RunCancellationRegistry cancellationRegistry,
            RunRecordService runRecordService
    ) {
        this.agentQueryService = agentQueryService;
        this.agentDefinitionService = agentDefinitionService;
        this.llmConfigService = llmConfigService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.cancellationRegistry = cancellationRegistry;
        this.runRecordService = runRecordService;
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
                        config.modelMappingJson(),
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
                                message.referencesJson(),
                                message.attachmentsJson(),
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
        return toRunDetailResponse(view);
    }

    @GetMapping("/sessions/{id}/runs")
    public java.util.List<RunSummaryResponse> listRunsBySession(@PathVariable("id") String id) {
        return agentQueryService.listRunsBySession(id).stream()
                .map(view -> new RunSummaryResponse(
                        view.runId(),
                        view.sessionId(),
                        view.status(),
                        view.detail(),
                        view.createdAt(),
                        view.updatedAt()
                ))
                .toList();
    }

    @GetMapping("/sessions/{id}/active-run")
    public ResponseEntity<RunDetailResponse> getActiveRun(@PathVariable("id") String id) {
        return agentQueryService.findActiveRun(id)
                .map(this::toRunDetailResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * List runs currently executing on this process. The UI uses this to render the
     * "running tasks" dock with a terminate button per row.
     */
    @GetMapping("/runs/active")
    public java.util.List<RunDetailResponse> listActiveRuns() {
        java.util.List<RunDetailResponse> result = new java.util.ArrayList<>();
        for (String runId : cancellationRegistry.activeRunIds()) {
            try {
                RunDetailView view = agentQueryService.getRun(runId);
                result.add(toRunDetailResponse(view));
            } catch (Exception ignored) {
                // DB race: run was registered in memory but the record is not yet visible.
                // Dropping the row here is acceptable — UI polls and will pick it up next tick.
            }
        }
        return result;
    }

    /**
     * Cancel an in-progress run. Three paths:
     *   1. Registry has the run → flip the cancel flag; orchestrator exits cleanly.
     *   2. Registry is empty but DB still shows a non-terminal status → force DB to CANCELLED
     *      (zombie reconcile). Makes the UI's terminate button idempotent and useful even
     *      after a backend restart.
     *   3. Neither applies → 404.
     */
    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<CancelRunResponse> cancelRun(@PathVariable("id") String id) {
        PermissionGate.require("session.terminate");
        boolean signalled = cancellationRegistry.requestCancel(id, "cancelled by user");
        if (signalled) {
            return ResponseEntity.ok(new CancelRunResponse(id, true, false,
                    "Cancel signal delivered; run will exit on next iteration checkpoint."));
        }
        // Try DB-fallback: if the DB still thinks the run is in-progress but no live context
        // exists on this process, the run is dead — flip the DB record so the UI isn't stuck.
        try {
            RunDetailView view = agentQueryService.getRun(id);
            if (view != null && isNonTerminal(view.status())) {
                runRecordService.updateStatus(id, RunStatus.CANCELLED,
                        "cancelled by user (no live execution on this process)");
                return ResponseEntity.ok(new CancelRunResponse(id, false, true,
                        "Run was not active on this process; forced DB status to CANCELLED."));
            }
        } catch (Exception ignored) {
            // Fall through to 404
        }
        return ResponseEntity.status(404)
                .body(new CancelRunResponse(id, false, false,
                        "Run not found or already in a terminal state."));
    }

    private boolean isNonTerminal(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toUpperCase()) {
            case "COMPLETED", "FAILED", "CANCELLED" -> false;
            default -> true;
        };
    }

    private RunDetailResponse toRunDetailResponse(RunDetailView view) {
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
                view.requestMessage(),
                view.requestReferencesJson(),
                view.requestAttachmentsJson(),
                view.planJson(),
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
                                message.referencesJson(),
                                message.attachmentsJson(),
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
                .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(artifact.artifact().name()))
                .contentLength(artifact.content().length)
                .body(new ByteArrayResource(artifact.content()));
    }

    /**
     * Content-Disposition 头的 {@code filename=} 参数只认 ASCII —— 中文文件名到浏览器就被替换成
     * 下划线，所以必须用 RFC 5987 的 {@code filename*=UTF-8''<percent-encoded>} 形式。
     *
     * <p>同时保留 {@code filename="..."} 作为 ASCII 降级值（把非 ASCII 字符换成 {@code _}），给不懂
     * filename* 的老客户端看。支持 filename* 的现代浏览器（Chrome/Firefox/Edge/Safari）会优先解析
     * filename*，从而拿到带中文的原始名。</p>
     */
    static String buildContentDisposition(String rawName) {
        String safeName = rawName == null || rawName.isBlank() ? "artifact" : rawName;
        String asciiFallback = toAsciiFallback(safeName);
        String utf8Encoded = java.net.URLEncoder.encode(safeName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + utf8Encoded;
    }

    /**
     * Keep ASCII chars and the common safe punctuation; collapse other bytes to {@code _} so old
     * clients still see a reasonable name. Avoids double quotes / CR / LF that would break the
     * header itself.
     */
    private static String toAsciiFallback(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x1F || c == 0x7F || c == '"' || c == '\\') {
                builder.append('_');
            } else if (c <= 0x7F) {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        String trimmed = builder.toString().trim();
        return trimmed.isEmpty() ? "artifact" : trimmed;
    }
}
