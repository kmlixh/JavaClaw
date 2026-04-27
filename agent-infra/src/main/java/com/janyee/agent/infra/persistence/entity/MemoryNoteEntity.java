package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "memory_note")
public class MemoryNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    /**
     * Scope governs retrieval visibility:
     *   'session' → only the originating session sees it (safe default for run_summary);
     *   'agent'   → all sessions of the same agent see it (explicit long-term memory);
     *   'global'  → visible to every agent (rare; reserved for future cross-agent knowledge).
     */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope = "session";

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope == null || scope.isBlank() ? "session" : scope;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.scope == null || this.scope.isBlank()) {
            this.scope = "session";
        }
        if (scopeType == null || scopeType.isBlank()) {
            scopeType = "SYSTEM";
            if (appId == null || appId.isBlank()) appId = "system-default";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== V23: multi-tenant scope fields (参看 SkillDefinitionEntity 注释) ===
    @Column(name = "scope_type", length = 16, nullable = false)
    private String scopeType;

    @Column(name = "scope_tenant_id", length = 64)
    private String scopeTenantId;

    @Column(name = "scope_user_id", length = 64)
    private String scopeUserId;

    @Column(name = "app_id", length = 64)
    private String appId;

    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeTenantId() { return scopeTenantId; }
    public void setScopeTenantId(String scopeTenantId) { this.scopeTenantId = scopeTenantId; }
    public String getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(String scopeUserId) { this.scopeUserId = scopeUserId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
}
