package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户表。管理员和普通用户共用这张 —— 角色由 {@link UserTenantRoleEntity} 决定。
 * 表名用 app_user 规避 "user" 在很多 DB 里是保留字的问题。
 */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "username", length = 64, nullable = false, unique = true)
    private String username;

    @Column(name = "email", length = 128)
    private String email;

    /** BCrypt(或占位字符串 PENDING_INITIALIZATION,由 FirstAdminPasswordInitializer 覆盖) */
    @Column(name = "password_hash", nullable = false, columnDefinition = "TEXT")
    private String passwordHash;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    /** ACTIVE / DISABLED / LOCKED */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "preferred_tenant_id", length = 64)
    private String preferredTenantId;

    @Column(name = "password_must_change", nullable = false)
    private boolean passwordMustChange;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPreferredTenantId() { return preferredTenantId; }
    public void setPreferredTenantId(String preferredTenantId) { this.preferredTenantId = preferredTenantId; }
    public boolean isPasswordMustChange() { return passwordMustChange; }
    public void setPasswordMustChange(boolean passwordMustChange) { this.passwordMustChange = passwordMustChange; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
