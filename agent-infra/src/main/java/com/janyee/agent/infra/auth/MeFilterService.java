package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.entity.auth.OauthClientEntity;
import com.janyee.agent.infra.persistence.entity.auth.TenantEntity;
import com.janyee.agent.infra.persistence.repository.auth.OauthClientRepository;
import com.janyee.agent.infra.persistence.repository.auth.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 给"当前登录用户的下拉过滤数据源"端点(/api/me/filterable-tenants 等)做的服务封装。
 *
 * <p>放在 agent-infra 是因为它依赖 JpaRepository,agent-web 反向引用不到。本服务把权限收窄
 * 逻辑(SessionVisibility 三档)落在这里,controller 只负责 HTTP 包装。</p>
 *
 * <p>返回视图刻意只暴露 id+name+tenantId 字段,不带 secret_hash / redirect_uris 等敏感数据。</p>
 */
@Service
public class MeFilterService {

    private final TenantRepository tenantRepository;
    private final OauthClientRepository oauthClientRepository;

    public MeFilterService(TenantRepository tenantRepository,
                           OauthClientRepository oauthClientRepository) {
        this.tenantRepository = tenantRepository;
        this.oauthClientRepository = oauthClientRepository;
    }

    /**
     * 当前用户可用来作为"租户过滤"下拉项的租户列表。sysadmin 返回全部 ACTIVE 租户,canFilter=true;
     * 其他用户返回自己的租户(一条),canFilter=false,前端把下拉禁用。
     */
    public TenantFilterResponse listFilterableTenants() {
        AuthPrincipal principal = SecurityContextHolder.current();
        SessionVisibility v = SessionVisibility.forPrincipal(principal);
        if (v.readAll()) {
            List<TenantView> rows = tenantRepository.findAll().stream()
                    .filter(t -> "ACTIVE".equals(t.getStatus()))
                    .sorted(Comparator.comparing(TenantEntity::getName, Comparator.nullsLast(String::compareTo)))
                    .map(MeFilterService::toTenantView)
                    .toList();
            return new TenantFilterResponse(rows, true);
        }
        String tid = principal == null ? null : principal.tenantId();
        if (tid == null || tid.isBlank()) {
            return new TenantFilterResponse(List.of(), false);
        }
        TenantEntity me = tenantRepository.findById(tid).orElse(null);
        TenantView view = me != null
                ? toTenantView(me)
                : new TenantView(tid, tid, tid, "TENANT", "ACTIVE");
        return new TenantFilterResponse(List.of(view), false);
    }

    /**
     * 当前用户可用来作为"应用过滤"下拉项的 OauthClient 列表。sysadmin 全部,其他用户限自己租户。
     * 始终额外注入一条 "system-default" 选项,因为主控台直登的 session.app_id 都是 system-default,
     * 不对应任何 oauth_client。
     */
    public AppFilterResponse listFilterableApps() {
        AuthPrincipal principal = SecurityContextHolder.current();
        SessionVisibility v = SessionVisibility.forPrincipal(principal);
        String myTenant = principal == null ? null : principal.tenantId();
        List<AppView> rows = oauthClientRepository.findAll().stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .filter(c -> v.readAll() || Objects.equals(c.getTenantId(), myTenant))
                .sorted(Comparator.comparing(OauthClientEntity::getDisplayName,
                        Comparator.nullsLast(String::compareTo)))
                .map(MeFilterService::toAppView)
                .toList();
        boolean hasSystemDefault = rows.stream().anyMatch(r -> "system-default".equals(r.appId()));
        if (!hasSystemDefault) {
            List<AppView> extended = new java.util.ArrayList<>(rows);
            extended.add(0, new AppView("system-default", "主控台 (system-default)", null));
            rows = extended;
        }
        return new AppFilterResponse(rows, true);
    }

    private static TenantView toTenantView(TenantEntity e) {
        return new TenantView(e.getId(), e.getCode(), e.getName(), e.getKind(), e.getStatus());
    }

    private static AppView toAppView(OauthClientEntity c) {
        return new AppView(c.getClientId(), c.getDisplayName(), c.getTenantId());
    }

    public record TenantView(String id, String code, String name, String kind, String status) {}
    public record AppView(String appId, String displayName, String tenantId) {}
    public record TenantFilterResponse(List<TenantView> rows, boolean canFilter) {}
    public record AppFilterResponse(List<AppView> rows, boolean canFilter) {}
}
