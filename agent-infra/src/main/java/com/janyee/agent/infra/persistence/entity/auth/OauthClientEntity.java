package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "oauth_client")
public class OauthClientEntity {

    @Id
    @Column(name = "client_id", length = 64, nullable = false)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, columnDefinition = "TEXT")
    private String clientSecretHash;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    /** JSON array of allowed redirect URIs */
    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    private String redirectUris;

    /** JSON array of scopes */
    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    private String scopes;

    /** ACTIVE | DISABLED —— 控制功能:DISABLED 时 OAuth 拒发 token,所有 chat / send 功能不可用。 */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /**
     * UI 显示开关 —— 控制嵌入页面(xmap-ol-front 等)的 AI 按钮 v-if。
     * displayEnabled=false 时按钮隐藏,功能不受影响;
     * status=DISABLED + displayEnabled=true:按钮也隐藏(AppStatusService 统一返回 enabled=false)。
     * 默认 true,沿用历史行为(从前没这字段 → status=ACTIVE 时默认显示)。
     */
    @Column(name = "display_enabled", nullable = false)
    private boolean displayEnabled = true;

    @Column(name = "owner_user_id", length = 64)
    private String ownerUserId;

    /** V27 后必填:外部应用归属哪个租户;list 时按 principal.tenantId 过滤。 */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (scopes == null) scopes = "[]";
        if (status == null) status = "ACTIVE";
        // V27 后 tenantId 必填,但匿名期(没 principal)兜底落 system,跟 V24 backfill 同口径
        if (tenantId == null || tenantId.isBlank()) tenantId = "system";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public void setClientSecretHash(String clientSecretHash) { this.clientSecretHash = clientSecretHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRedirectUris() { return redirectUris; }
    public void setRedirectUris(String redirectUris) { this.redirectUris = redirectUris; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isDisplayEnabled() { return displayEnabled; }
    public void setDisplayEnabled(boolean displayEnabled) { this.displayEnabled = displayEnabled; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
