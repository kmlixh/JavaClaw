package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "llm_provider_config")
public class LlmProviderConfigEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "model", nullable = false, length = 255)
    private String model;

    @Column(name = "model_mapping_json", nullable = false, columnDefinition = "TEXT")
    private String modelMappingJson;

    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    @Column(name = "api_key", nullable = false, columnDefinition = "TEXT")
    private String apiKey;

    @Column(name = "chat_path", nullable = false, length = 255)
    private String chatPath;

    @Column(name = "stream_enabled", nullable = false)
    private boolean streamEnabled;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "is_default", nullable = false)
    private boolean defaultConfig;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ===== 通用 scope =====
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
    public String getScopeTenantId() { return scopeTenantId; }
    public void setScopeTenantId(String scopeTenantId) { this.scopeTenantId = scopeTenantId; }
    public String getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(String scopeUserId) { this.scopeUserId = scopeUserId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModelMappingJson() {
        return modelMappingJson;
    }

    public void setModelMappingJson(String modelMappingJson) {
        this.modelMappingJson = modelMappingJson;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }

    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(boolean defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.modelMappingJson == null || this.modelMappingJson.isBlank()) {
            this.modelMappingJson = "{\"models\":[]}";
        }
        if (this.scopeType == null || this.scopeType.isBlank()) {
            this.scopeType = "SYSTEM";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
