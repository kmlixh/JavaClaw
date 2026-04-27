package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionEntity.Pk.class)
public class RolePermissionEntity {

    @Id
    @Column(name = "role_id", length = 64, nullable = false)
    private String roleId;

    @Id
    @Column(name = "permission_code", length = 64, nullable = false)
    private String permissionCode;

    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }

    public static class Pk implements Serializable {
        private String roleId;
        private String permissionCode;
        public Pk() {}
        public Pk(String roleId, String permissionCode) {
            this.roleId = roleId;
            this.permissionCode = permissionCode;
        }
        public String getRoleId() { return roleId; }
        public void setRoleId(String roleId) { this.roleId = roleId; }
        public String getPermissionCode() { return permissionCode; }
        public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(roleId, pk.roleId) && Objects.equals(permissionCode, pk.permissionCode);
        }
        @Override
        public int hashCode() { return Objects.hash(roleId, permissionCode); }
    }
}
