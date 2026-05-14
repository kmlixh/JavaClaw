package com.janyee.agent.web.controller;

import com.janyee.agent.infra.auth.MeFilterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "当前登录用户可见的下拉数据源" —— 给会话列表 / 审批 / 任何按租户/应用过滤的页面填充下拉。
 *
 * <p>跟 {@code /api/admin/tenants} (要求 tenant.manage 权限) 不同,本端点接受所有登录用户,
 * 返回值由 {@link MeFilterService} 按 SessionVisibility 自动收窄:</p>
 * <ul>
 *   <li>sysadmin (read.all) → 全部租户 + 全部 OauthClient,canFilter=true</li>
 *   <li>租户管理员 / 普通用户 → 仅本租户 + 本租户下的 OauthClient,canFilter=false(下拉锁定)</li>
 * </ul>
 *
 * 只暴露 id+name+tenantId,不带任何 secret 字段。
 */
@RestController
@RequestMapping("/api/me")
public class MeFilterController {

    private final MeFilterService service;

    public MeFilterController(MeFilterService service) {
        this.service = service;
    }

    @GetMapping("/filterable-tenants")
    public MeFilterService.TenantFilterResponse listFilterableTenants() {
        return service.listFilterableTenants();
    }

    @GetMapping("/filterable-apps")
    public MeFilterService.AppFilterResponse listFilterableApps() {
        return service.listFilterableApps();
    }
}
