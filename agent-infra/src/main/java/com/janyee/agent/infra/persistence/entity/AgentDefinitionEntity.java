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
        if (scopeType == null || scopeType.isBlank()) {
            scopeType = "SYSTEM";
            if (appId == null || appId.isBlank()) appId = "system-default";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== 通用 scope:与 skill / knowledge / tool / llm / datasource 对齐 =====
    // scope_type:SYSTEM | PUBLIC | TENANT | APP | USER
    // 旧的 visibility 列已经废弃(DB 那边 NULL 容忍,数据搬到 scope_type)。
    @Column(name = "scope_type", length = 32, nullable = false)
    private String scopeType;

    @Column(name = "scope_tenant_id", length = 64)
    private String scopeTenantId;

    @Column(name = "scope_user_id", length = 64)
    private String scopeUserId;

    @Column(name = "app_id", length = 64)
    private String appId;

    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    /** @deprecated 用 getScopeType,visibility 字段已下线 */
    @Deprecated public String getVisibility() { return scopeType; }
    /** @deprecated 用 setScopeType,visibility 字段已下线 */
    @Deprecated public void setVisibility(String value) { this.scopeType = value; }
    public String getScopeTenantId() { return scopeTenantId; }
    public void setScopeTenantId(String scopeTenantId) { this.scopeTenantId = scopeTenantId; }
    public String getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(String scopeUserId) { this.scopeUserId = scopeUserId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
}
