package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 全局平铺的权限 code 目录。所有租户共享同一套能力名。 */
@Entity
@Table(name = "permission")
public class PermissionEntity {

    @Id
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    /** menu | data | admin | system —— 仅 UI 分组用,不参与鉴权。 */
    @Column(name = "category", length = 32, nullable = false)
    private String category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
