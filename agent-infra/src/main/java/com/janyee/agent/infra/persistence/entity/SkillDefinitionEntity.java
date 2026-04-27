package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "skill_definition")
public class SkillDefinitionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    /**
     * Legacy column from the pre-V19 1:N model. After V19 the source of truth for
     * skill↔agent is {@code skill_agent_binding}. This field may be null on newly
     * created skills; existing rows keep their value but no enforcement reads it.
     */
    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "skill_name", nullable = false, length = 128)
    private String skillName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "prompt_template", nullable = false, columnDefinition = "TEXT")
    private String promptTemplate;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * Optional JSON array of keywords. If the user's message contains any of them but the
     * agent doesn't have this skill bound, SimpleAgentRunner aborts with a clear error so
     * the LLM doesn't flail. Format: {@code ["覆盖分析","扇区统计"]}. Null/empty = no matching.
     */
    @Column(name = "trigger_keywords", columnDefinition = "TEXT")
    private String triggerKeywords;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getTriggerKeywords() { return triggerKeywords; }
    public void setTriggerKeywords(String triggerKeywords) { this.triggerKeywords = triggerKeywords; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        // P1 兼容:没显式设置 scope 的新行默认 SYSTEM scope,沿用现有"全局可见"行为。
        // P2/P3 鉴权接入后,CRUD 入口会从 SecurityContext 主动设置 scope,不走这个默认。
        if (scopeType == null || scopeType.isBlank()) {
            scopeType = "SYSTEM";
            if (appId == null || appId.isBlank()) appId = "system-default";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ===== V23: multi-tenant scope fields ==================================
    // scope_type: SYSTEM | TENANT | USER
    //   SYSTEM → scope_tenant_id/scope_user_id NULL
    //   TENANT → scope_tenant_id filled
    //   USER   → scope_user_id filled (cross-tenant visible to this user)
    @jakarta.persistence.Column(name = "scope_type", length = 16, nullable = false)
    private String scopeType;

    @jakarta.persistence.Column(name = "scope_tenant_id", length = 64)
    private String scopeTenantId;

    @jakarta.persistence.Column(name = "scope_user_id", length = 64)
    private String scopeUserId;

    @jakarta.persistence.Column(name = "app_id", length = 64)
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
