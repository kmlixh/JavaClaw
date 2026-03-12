package com.janyee.agent.web.controller;

import com.janyee.agent.api.AgentDefinitionResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.api.SessionMessageResponse;
import com.janyee.agent.api.ToolAuditLogResponse;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.SessionDetailView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final AgentQueryService agentQueryService;
    private final AgentDefinitionService agentDefinitionService;

    public QueryController(AgentQueryService agentQueryService, AgentDefinitionService agentDefinitionService) {
        this.agentQueryService = agentQueryService;
        this.agentDefinitionService = agentDefinitionService;
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

    @GetMapping("/sessions/{id}")
    public SessionDetailResponse getSession(@PathVariable("id") String id) {
        SessionDetailView view = agentQueryService.getSession(id);
        return new SessionDetailResponse(
                view.sessionId(),
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

    @GetMapping("/runs/{id}")
    public RunDetailResponse getRun(@PathVariable("id") String id) {
        RunDetailView view = agentQueryService.getRun(id);
        return new RunDetailResponse(
                view.runId(),
                view.sessionId(),
                view.agentId(),
                view.userId(),
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
                        .toList()
        );
    }
}
