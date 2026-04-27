package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 用户级权限覆盖。effect=GRANT 单独加能力,effect=DENY 在角色赋权基础上单独禁。
 * 判定算法:DENY > 角色 GRANT > user_permission GRANT。
 */
@Entity
@Table(name = "user_permission")
@IdClass(UserPermissionEntity.Pk.class)
public class UserPermissionEntity {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Id
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Id
    @Column(name = "permission_code", length = 64, nullable = false)
    private String permissionCode;

    /** GRANT | DENY */
    @Column(name = "effect", length = 16, nullable = false)
    private String effect;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @PrePersist
    void onCreate() {
        if (grantedAt == null) grantedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }

    public static class Pk implements Serializable {
        private String userId;
        private String tenantId;
        private String permissionCode;
        public Pk() {}
        public Pk(String userId, String tenantId, String permissionCode) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.permissionCode = permissionCode;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getPermissionCode() { return permissionCode; }
        public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(permissionCode, pk.permissionCode);
        }
        @Override
        public int hashCode() { return Objects.hash(userId, tenantId, permissionCode); }
    }
}
