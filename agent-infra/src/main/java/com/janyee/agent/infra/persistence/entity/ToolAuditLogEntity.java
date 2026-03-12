package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "tool_audit_log")
public class ToolAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "phase", nullable = false, length = 64)
    private String phase;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "executed")
    private Boolean executed;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "arguments_json", columnDefinition = "TEXT")
    private String argumentsJson;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getPhase() {
        return phase;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    public Boolean getSuccess() {
        return success;
    }

    public Boolean getExecuted() {
        return executed;
    }

    public String getReason() {
        return reason;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public void setApprovalRequired(boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public void setExecuted(Boolean executed) {
        this.executed = executed;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
