package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.entity.auth.AppUserEntity;
import com.janyee.agent.infra.persistence.entity.auth.TenantEntity;
import com.janyee.agent.infra.persistence.entity.auth.TenantMenuEntity;
import com.janyee.agent.infra.persistence.entity.auth.UserTenantRoleEntity;
import com.janyee.agent.infra.persistence.repository.auth.AppUserRepository;
import com.janyee.agent.infra.persistence.repository.auth.TenantMenuRepository;
import com.janyee.agent.infra.persistence.repository.auth.TenantRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserTenantRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录 / 切换租户 / 构造 whoami 视图。
 *
 * <p>故意不把 Spring Security 拖进来 —— 认证链很短:username/password → BCrypt → JWT;
 * 不需要 Filter 链 / UserDetailsService 的那套抽象。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantMenuRepository tenantMenuRepository;
    private final PermissionResolver permissionResolver;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public AuthService(
            AppUserRepository userRepository,
            TenantRepository tenantRepository,
            UserTenantRoleRepository userTenantRoleRepository,
            TenantMenuRepository tenantMenuRepository,
            PermissionResolver permissionResolver
    ) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantMenuRepository = tenantMenuRepository;
        this.permissionResolver = permissionResolver;
    }

    /** 认证成功返回 user entity + 推断出的活跃租户。密码错 / 帐号禁用抛 AuthException。 */
    @Transactional
    public Authenticated authenticate(String username, String password) {
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthException("INVALID_CREDENTIALS", "用户名或密码错误"));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AuthException("USER_" + user.getStatus(), "账户状态异常:" + user.getStatus());
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new AuthException("INVALID_CREDENTIALS", "用户名或密码错误");
        }
        // 选择活跃租户:优先 preferred_tenant_id,其次第一个有绑定的 tenant
        List<UserTenantRoleEntity> bindings = userTenantRoleRepository.findByUserId(user.getId());
        if (bindings.isEmpty()) {
            throw new AuthException("NO_TENANT", "该用户不属于任何租户,无法登录");
        }
        String activeTenantId = resolveActiveTenant(user, bindings);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return new Authenticated(user, activeTenantId);
    }

    /** 构造 whoami 响应。不在这里签 token —— Controller 负责签发 + 放 cookie。 */
    public Whoami buildWhoami(String userId, String activeTenantId) {
        AppUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND", "用户不存在"));
        List<UserTenantRoleEntity> bindings = userTenantRoleRepository.findByUserId(userId);
        if (bindings.isEmpty()) {
            return new Whoami(
                    user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                    user.isPasswordMustChange(),
                    activeTenantId, List.of(), Set.of(), List.of()
            );
        }
        // 列出用户能访问的所有租户(去重)
        List<String> tenantIds = bindings.stream()
                .map(UserTenantRoleEntity::getTenantId)
                .distinct()
                .toList();
        List<TenantView> tenants = tenantRepository.findAllById(tenantIds).stream()
                .map(t -> new TenantView(t.getId(), t.getCode(), t.getName(), t.getKind()))
                .sorted(Comparator.comparing(TenantView::code))
                .toList();
        // 校验 activeTenantId 是用户真的能访问的,不是就回退到第一个
        final String effectiveTenantId;
        if (activeTenantId == null || tenantIds.stream().noneMatch(id -> id.equals(activeTenantId))) {
            effectiveTenantId = tenantIds.get(0);
        } else {
            effectiveTenantId = activeTenantId;
        }
        Set<String> perms = permissionResolver.resolve(userId, effectiveTenantId);
        List<TenantMenuEntity> menuRows = tenantMenuRepository.findByTenantIdOrderBySortOrderAsc(effectiveTenantId);
        // 菜单:tenant_menu 开启 AND 用户有 menu.<code> 权限
        List<MenuItem> menus = menuRows.stream()
                .filter(TenantMenuEntity::isEnabled)
                .filter(m -> perms.contains("menu." + m.getMenuCode()))
                .map(m -> new MenuItem(m.getMenuCode(), m.getSortOrder()))
                .toList();
        return new Whoami(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.isPasswordMustChange(),
                effectiveTenantId, tenants, perms, menus
        );
    }

    /** 切换活跃租户:验证归属后返回新的 activeTenantId + 刷 preferred_tenant_id。 */
    @Transactional
    public Authenticated switchTenant(String userId, String targetTenantId) {
        List<UserTenantRoleEntity> bindings = userTenantRoleRepository.findByUserId(userId);
        boolean belongs = bindings.stream().anyMatch(b -> b.getTenantId().equals(targetTenantId));
        if (!belongs) {
            throw new AuthException("TENANT_FORBIDDEN", "当前用户未绑定到目标租户");
        }
        AppUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND", "用户不存在"));
        user.setPreferredTenantId(targetTenantId);
        userRepository.save(user);
        return new Authenticated(user, targetTenantId);
    }

    /** 改密码:校验旧密码 → 写新 BCrypt → 清 must_change 标记。 */
    @Transactional
    public void changePassword(String userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new AuthException("WEAK_PASSWORD", "新密码至少 8 位");
        }
        AppUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND", "用户不存在"));
        if (!encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AuthException("INVALID_CREDENTIALS", "旧密码错误");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordMustChange(false);
        userRepository.save(user);
        log.info("auth.change_password userId={}", userId);
    }

    private String resolveActiveTenant(AppUserEntity user, List<UserTenantRoleEntity> bindings) {
        if (user.getPreferredTenantId() != null) {
            boolean stillBound = bindings.stream()
                    .anyMatch(b -> b.getTenantId().equals(user.getPreferredTenantId()));
            if (stillBound) return user.getPreferredTenantId();
        }
        return bindings.get(0).getTenantId();
    }

    // ---------------------------------------------------------------------------------------
    // Value types
    // ---------------------------------------------------------------------------------------

    public record Authenticated(AppUserEntity user, String activeTenantId) {}

    public record Whoami(
            String userId,
            String username,
            String displayName,
            String email,
            boolean passwordMustChange,
            String activeTenantId,
            List<TenantView> tenants,
            Set<String> permissions,
            List<MenuItem> menus
    ) {}

    public record TenantView(String id, String code, String name, String kind) {}
    public record MenuItem(String code, int sortOrder) {}

    public static class AuthException extends RuntimeException {
        private final String code;
        public AuthException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
