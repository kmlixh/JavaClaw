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

@Entity
@Table(name = "user_tenant_role")
@IdClass(UserTenantRoleEntity.Pk.class)
public class UserTenantRoleEntity {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Id
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Id
    @Column(name = "role_id", length = 64, nullable = false)
    private String roleId;

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
    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }

    public static class Pk implements Serializable {
        private String userId;
        private String tenantId;
        private String roleId;
        public Pk() {}
        public Pk(String userId, String tenantId, String roleId) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.roleId = roleId;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getRoleId() { return roleId; }
        public void setRoleId(String roleId) { this.roleId = roleId; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(roleId, pk.roleId);
        }
        @Override
        public int hashCode() { return Objects.hash(userId, tenantId, roleId); }
    }
}
