package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 租户自定义菜单可见性。与 permission(code='menu.xxx') 是 AND 关系:
 * 只有 enabled=TRUE 且用户有对应 menu.xxx 权限时菜单才出现。
 */
@Entity
@Table(name = "tenant_menu")
@IdClass(TenantMenuEntity.Pk.class)
public class TenantMenuEntity {

    @Id
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Id
    @Column(name = "menu_code", length = 64, nullable = false)
    private String menuCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMenuCode() { return menuCode; }
    public void setMenuCode(String menuCode) { this.menuCode = menuCode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public static class Pk implements Serializable {
        private String tenantId;
        private String menuCode;
        public Pk() {}
        public Pk(String tenantId, String menuCode) {
            this.tenantId = tenantId;
            this.menuCode = menuCode;
        }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getMenuCode() { return menuCode; }
        public void setMenuCode(String menuCode) { this.menuCode = menuCode; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(tenantId, pk.tenantId) && Objects.equals(menuCode, pk.menuCode);
        }
        @Override
        public int hashCode() { return Objects.hash(tenantId, menuCode); }
    }
}
