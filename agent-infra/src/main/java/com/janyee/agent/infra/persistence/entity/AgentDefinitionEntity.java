package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_definition")
public class AgentDefinitionEntity {

    @Id
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt = "";

    @Column(name = "agent_markdown", nullable = false, columnDefinition = "TEXT")
    private String agentMarkdown = "";

    @Column(name = "memory_markdown", nullable = false, columnDefinition = "TEXT")
    private String memoryMarkdown = "";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt == null ? "" : systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt == null ? "" : systemPrompt; }
    public String getAgentMarkdown() { return agentMarkdown == null ? "" : agentMarkdown; }
    public void setAgentMarkdown(String agentMarkdown) { this.agentMarkdown = agentMarkdown == null ? "" : agentMarkdown; }
    public String getMemoryMarkdown() { return memoryMarkdown == null ? "" : memoryMarkdown; }
    public void setMemoryMarkdown(String memoryMarkdown) { this.memoryMarkdown = memoryMarkdown == null ? "" : memoryMarkdown; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        // P1 兼容:新 agent 默认 SYSTEM 可见,现有 CRUD 不受影响
        if (visibility == null || visibility.isBlank()) {
            visibility = "SYSTEM";
            if (appId == null || appId.isBlank()) appId = "system-default";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== V23: agent 可见性 ================================================
    // visibility: SYSTEM | TENANT | USER
    //   SYSTEM → 任何用户可见(V24 backfill 把现有 agent 全部标 SYSTEM)
    //   TENANT → scope_tenant_id 必填,租户内可见
    //   USER   → scope_user_id 必填,owner 自己和通过 user_agent_binding "收藏" 该 agent 的用户可见
    @Column(name = "visibility", length = 16, nullable = false)
    private String visibility;

    @Column(name = "scope_tenant_id", length = 64)
    private String scopeTenantId;

    @Column(name = "scope_user_id", length = 64)
    private String scopeUserId;

    @Column(name = "app_id", length = 64)
    private String appId;

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getScopeTenantId() { return scopeTenantId; }
    public void setScopeTenantId(String scopeTenantId) { this.scopeTenantId = scopeTenantId; }
    public String getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(String scopeUserId) { this.scopeUserId = scopeUserId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
}
