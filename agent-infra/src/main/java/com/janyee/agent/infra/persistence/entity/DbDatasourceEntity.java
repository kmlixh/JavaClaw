package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "db_datasource")
public class DbDatasourceEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "jdbc_url", nullable = false, length = 512)
    private String jdbcUrl;

    @Column(name = "username", nullable = false, length = 128)
    private String username;

    @Column(name = "password", nullable = false, length = 512)
    private String password;

    @Column(name = "dialect", length = 32)
    private String dialect;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (scopeType == null || scopeType.isBlank()) {
            scopeType = "SYSTEM";
            if (appId == null || appId.isBlank()) appId = "system-default";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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
