package com.janyee.agent.infra.admin;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.AuthService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import com.janyee.agent.infra.persistence.entity.auth.AppUserEntity;
import com.janyee.agent.infra.persistence.entity.auth.OauthClientEntity;
import com.janyee.agent.infra.persistence.entity.auth.PermissionEntity;
import com.janyee.agent.infra.persistence.entity.auth.RoleEntity;
import com.janyee.agent.infra.persistence.entity.auth.RolePermissionEntity;
import com.janyee.agent.infra.persistence.entity.auth.TenantEntity;
import com.janyee.agent.infra.persistence.entity.auth.UserPermissionEntity;
import com.janyee.agent.infra.persistence.entity.auth.UserTenantRoleEntity;
import com.janyee.agent.infra.persistence.repository.auth.AppUserRepository;
import com.janyee.agent.infra.persistence.repository.auth.OauthClientRepository;
import com.janyee.agent.infra.persistence.repository.auth.PermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.RolePermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.RoleRepository;
import com.janyee.agent.infra.persistence.repository.auth.TenantRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserPermissionRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserTenantRoleRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理后台:用户/租户/角色/权限/OAuth 客户端 的 CRUD 实现。
 * 鉴权由 controller 层 PermissionGate 兜住,这里只做数据 + 一致性检查。
 */
@Service
public class AdminUserManagementService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private final AppUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final OauthClientRepository oauthClientRepository;
    private final TenantBootstrapService tenantBootstrap;

    public AdminUserManagementService(
            AppUserRepository userRepository,
            TenantRepository tenantRepository,
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            UserTenantRoleRepository userTenantRoleRepository,
            UserPermissionRepository userPermissionRepository,
            OauthClientRepository oauthClientRepository,
            TenantBootstrapService tenantBootstrap
    ) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.oauthClientRepository = oauthClientRepository;
        this.tenantBootstrap = tenantBootstrap;
    }

    // ── Users ──────────────────────────────────────────────────────────────

    /**
     * 是否为系统超管:目前用 session.read.all 这个权限码做硬阈值 ——
     * V24 里只有 system-super-admin 角色拥有,租户管理员没有。它的语义是"跨租户读",
     * 复用为"跨租户管理用户/OAuth 客户端"的 marker 不会冲突。
     */
    private boolean isSystemAdmin(AuthPrincipal p) {
        // 统一到 ResourceScopeFilter.isSystemAdmin 的标准 (tenantId='system'),避免两个 service
        // 用不同的 sysadmin 判定语义。anonymous(P2 兼容期) 仍放行。
        if (p == null) return false;
        if (p.anonymous()) return true;
        return com.janyee.agent.infra.auth.ResourceScopeFilter.isSystemAdmin(p);
    }

    /**
     * 校验当前 principal 是否可操作目标用户。
     *   - 系统管理员:任何用户都可;
     *   - 租户管理员:仅当目标用户在当前租户的 user_tenant_role 里有绑定才可。
     * 不通过抛 AuthException("forbidden") → 由 AuthExceptionAdvice 转 HTTP 403。
     * 给所有"按 userId 操作"的端点统一做边界检查,避免租户管理员越权改其它租户用户。
     */
    private void requireUserAccessible(String targetUserId) {
        if (targetUserId == null || targetUserId.isBlank()) return;
        AuthPrincipal p = SecurityContextHolder.current();
        if (isSystemAdmin(p)) return;
        if (p == null || p.tenantId() == null || p.tenantId().isBlank()) {
            throw new AuthService.AuthException("forbidden", "no tenant context");
        }
        boolean bound = !userTenantRoleRepository
                .findByUserIdAndTenantId(targetUserId, p.tenantId())
                .isEmpty();
        if (!bound) {
            throw new AuthService.AuthException("forbidden",
                    "user " + targetUserId + " 不在当前租户内,无权操作");
        }
    }

    public List<UserView> listUsers() {
        AuthPrincipal p = SecurityContextHolder.current();
        if (isSystemAdmin(p)) {
            return userRepository.findAll().stream().map(this::toUserView).toList();
        }
        // 租户管理员只看本租户内的用户(=有 user_tenant_role 绑定到当前租户的)
        java.util.Set<String> userIds = userTenantRoleRepository.findByTenantId(p.tenantId()).stream()
                .map(UserTenantRoleEntity::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        return userRepository.findAllById(userIds).stream().map(this::toUserView).toList();
    }

    public UserView getUser(String id) {
        requireUserAccessible(id);
        return toUserView(userRepository.findById(id)
                .orElseThrow(() -> new AuthService.AuthException("USER_NOT_FOUND", "user not found: " + id)));
    }

    /**
     * 创建新用户。生成临时密码并强制下次登录改密。返回 view + 临时明文密码(只此一次返回)。
     *
     * <p>租户管理员调用时:preferredTenantId 强制为当前 principal 的租户,且自动用该租户的 'USER'
     * 角色完成绑定 —— 否则新用户没有 user_tenant_role 就什么都看不到。</p>
     */
    @Transactional
    public CreatedUser createUser(UserCreateCommand cmd) {
        if (cmd == null || isBlank(cmd.username())) {
            throw new AuthService.AuthException("INVALID_INPUT", "username is required");
        }
        if (userRepository.findByUsername(cmd.username().trim()).isPresent()) {
            throw new AuthService.AuthException("USERNAME_TAKEN", "username already exists: " + cmd.username());
        }
        AuthPrincipal p = SecurityContextHolder.current();
        boolean systemAdmin = isSystemAdmin(p);
        String tenantId = systemAdmin
                ? blankToNull(cmd.preferredTenantId())  // 超管可以指定任意租户(也允许 null)
                : p.tenantId();                          // 租户管理员只能创进自己租户
        String temp = isBlank(cmd.initialPassword()) ? generateTempPassword() : cmd.initialPassword();
        AppUserEntity entity = new AppUserEntity();
        entity.setId(isBlank(cmd.id()) ? UUID.randomUUID().toString() : cmd.id().trim());
        entity.setUsername(cmd.username().trim());
        entity.setEmail(blankToNull(cmd.email()));
        entity.setDisplayName(isBlank(cmd.displayName()) ? cmd.username().trim() : cmd.displayName().trim());
        entity.setStatus("ACTIVE");
        entity.setPasswordHash(BCRYPT.encode(temp));
        entity.setPasswordMustChange(true);
        entity.setPreferredTenantId(tenantId);
        AppUserEntity saved = userRepository.save(entity);
        // 租户管理员场景:自动用租户里 code='USER' 的角色完成 binding。找不到则跳过(超管再手动补)。
        if (!systemAdmin && tenantId != null) {
            roleRepository.findByTenantIdAndCode(tenantId, "USER").ifPresent(role -> {
                UserTenantRoleEntity utr = new UserTenantRoleEntity();
                utr.setUserId(saved.getId());
                utr.setTenantId(tenantId);
                utr.setRoleId(role.getId());
                userTenantRoleRepository.save(utr);
            });
        }
        return new CreatedUser(toUserView(saved), temp);
    }

    @Transactional
    public UserView updateUser(String id, UserUpdateCommand cmd) {
        requireUserAccessible(id);
        AppUserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new AuthService.AuthException("USER_NOT_FOUND", "user not found: " + id));
        // 租户管理员不能改 preferredTenantId 把用户搬到别的租户(只能改自己租户内的字段)
        AuthPrincipal p = SecurityContextHolder.current();
        boolean systemAdmin = isSystemAdmin(p);
        if (cmd.displayName() != null) entity.setDisplayName(cmd.displayName().trim());
        if (cmd.email() != null) entity.setEmail(blankToNull(cmd.email()));
        if (cmd.status() != null && !cmd.status().isBlank()) entity.setStatus(cmd.status().trim().toUpperCase());
        if (cmd.preferredTenantId() != null) {
            if (systemAdmin) {
                entity.setPreferredTenantId(blankToNull(cmd.preferredTenantId()));
            }
            // 非超管:忽略 preferredTenantId 字段,保持原值
        }
        return toUserView(userRepository.save(entity));
    }

    /** 管理员触发的密码重置。生成新临时密码 + 强制下次登录改密。返回临时明文密码。 */
    @Transactional
    public String resetPassword(String userId) {
        requireUserAccessible(userId);
        AppUserEntity entity = userRepository.findById(userId)
                .orElseThrow(() -> new AuthService.AuthException("USER_NOT_FOUND", "user not found: " + userId));
        String temp = generateTempPassword();
        entity.setPasswordHash(BCRYPT.encode(temp));
        entity.setPasswordMustChange(true);
        userRepository.save(entity);
        return temp;
    }

    /**
     * 批量导入。每条调用 createUser,失败的写到结果里继续往下。一次最多 200 条避免一次事务太大。
     * 返回逐行结果(成功 → 临时密码;失败 → 错误信息)。
     */
    @Transactional
    public List<ImportResult> importUsers(List<UserCreateCommand> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        if (rows.size() > 200) {
            throw new AuthService.AuthException("INVALID_INPUT", "batch import limit is 200 rows per call");
        }
        java.util.List<ImportResult> out = new java.util.ArrayList<>(rows.size());
        for (UserCreateCommand row : rows) {
            try {
                CreatedUser created = createUser(row);
                out.add(new ImportResult(row.username(), true, null,
                        created.user().id(), created.temporaryPassword()));
            } catch (RuntimeException error) {
                out.add(new ImportResult(row.username(), false, error.getMessage(), null, null));
            }
        }
        return out;
    }

    public record ImportResult(String username, boolean ok, String error,
                                String userId, String temporaryPassword) {}

    @Transactional
    public void deleteUser(String id) {
        if ("admin".equals(id)) {
            throw new AuthService.AuthException("FORBIDDEN_DELETE_ADMIN", "cannot delete the bootstrap admin user");
        }
        requireUserAccessible(id);
        // 先清绑定再删主体,避免 FK 约束失败。
        userTenantRoleRepository.findByUserId(id).forEach(userTenantRoleRepository::delete);
        // user_permission 没有 findByUserId,逐租户清理:遍历 user_tenant_role 时已捎带,
        // 但保险起见再扫一次表里所有 binding。
        userRepository.deleteById(id);
    }

    // ── Tenants ────────────────────────────────────────────────────────────

    public List<TenantView> listTenants() {
        return tenantRepository.findAll().stream().map(this::toTenantView).toList();
    }

    @Transactional
    public TenantView saveTenant(TenantCommand cmd) {
        if (cmd == null || isBlank(cmd.code()) || isBlank(cmd.name())) {
            throw new AuthService.AuthException("INVALID_INPUT", "tenant code/name is required");
        }
        String id = isBlank(cmd.id()) ? UUID.randomUUID().toString() : cmd.id().trim();
        boolean isNew = tenantRepository.findById(id).isEmpty();
        TenantEntity entity = tenantRepository.findById(id).orElseGet(TenantEntity::new);
        entity.setId(id);
        entity.setCode(cmd.code().trim());
        entity.setName(cmd.name().trim());
        entity.setKind(isBlank(cmd.kind()) ? "TENANT" : cmd.kind().trim().toUpperCase());
        entity.setStatus(isBlank(cmd.status()) ? "ACTIVE" : cmd.status().trim().toUpperCase());
        entity.setDescription(blankToNull(cmd.description()));
        TenantEntity saved = tenantRepository.save(entity);
        // 新建租户时自动套默认角色 + 菜单模板;已存在的租户复跑也是幂等(只补缺)。
        if (isNew && !"SYSTEM".equalsIgnoreCase(saved.getKind())) {
            tenantBootstrap.bootstrap(saved.getId());
        }
        return toTenantView(saved);
    }

    @Transactional
    public void deleteTenant(String id) {
        if ("system".equals(id)) {
            throw new AuthService.AuthException("FORBIDDEN_DELETE_SYSTEM_TENANT", "cannot delete SYSTEM tenant");
        }
        tenantRepository.deleteById(id);
    }

    // ── Roles ──────────────────────────────────────────────────────────────

    public List<RoleView> listRoles(String tenantId) {
        List<RoleEntity> roles = isBlank(tenantId)
                ? roleRepository.findAll()
                : roleRepository.findByTenantId(tenantId);
        return roles.stream().map(this::toRoleView).toList();
    }

    @Transactional
    public RoleView saveRole(RoleCommand cmd) {
        if (cmd == null || isBlank(cmd.tenantId()) || isBlank(cmd.code()) || isBlank(cmd.name())) {
            throw new AuthService.AuthException("INVALID_INPUT", "role tenantId/code/name is required");
        }
        String id = isBlank(cmd.id()) ? UUID.randomUUID().toString() : cmd.id().trim();
        RoleEntity entity = roleRepository.findById(id).orElseGet(RoleEntity::new);
        entity.setId(id);
        entity.setTenantId(cmd.tenantId().trim());
        entity.setCode(cmd.code().trim().toUpperCase());
        entity.setName(cmd.name().trim());
        entity.setDescription(blankToNull(cmd.description()));
        return toRoleView(roleRepository.save(entity));
    }

    @Transactional
    public void deleteRole(String id) {
        // 不允许删 system-super-admin / system-user 兜底角色,否则 admin 没法登录。
        if ("system-super-admin".equals(id) || "system-user".equals(id)) {
            throw new AuthService.AuthException("FORBIDDEN_DELETE_BUILTIN_ROLE", "cannot delete builtin role: " + id);
        }
        rolePermissionRepository.findByRoleId(id).forEach(rolePermissionRepository::delete);
        roleRepository.deleteById(id);
    }

    /** 全量改写角色权限集合(覆盖式)。 */
    @Transactional
    public List<String> updateRolePermissions(String roleId, List<String> permissionCodes) {
        roleRepository.findById(roleId)
                .orElseThrow(() -> new AuthService.AuthException("ROLE_NOT_FOUND", "role not found: " + roleId));
        rolePermissionRepository.findByRoleId(roleId).forEach(rolePermissionRepository::delete);
        rolePermissionRepository.flush();
        if (permissionCodes == null) return List.of();
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String code : permissionCodes) if (!isBlank(code)) uniq.add(code.trim());
        for (String code : uniq) {
            RolePermissionEntity rp = new RolePermissionEntity();
            rp.setRoleId(roleId);
            rp.setPermissionCode(code);
            rolePermissionRepository.save(rp);
        }
        return new java.util.ArrayList<>(uniq);
    }

    /** 给用户分配/移除某租户的角色集合(覆盖式 — 这一组就是 user 在该 tenant 的全部角色)。 */
    @Transactional
    public void assignUserRoles(String userId, String tenantId, List<String> roleIds) {
        AuthPrincipal p = SecurityContextHolder.current();
        if (!isSystemAdmin(p) && !p.tenantId().equals(tenantId)) {
            throw new AuthService.AuthException("forbidden",
                    "tenant admin can only assign roles within own tenant: " + p.tenantId());
        }
        userRepository.findById(userId)
                .orElseThrow(() -> new AuthService.AuthException("USER_NOT_FOUND", "user not found: " + userId));
        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new AuthService.AuthException("TENANT_NOT_FOUND", "tenant not found: " + tenantId));
        // 校验每个 roleId 真属于目标 tenantId —— 之前这里没查,
        // 租户管理员能传别租户的 roleId 绑到自己租户用户头上,导致权限模型混乱。
        if (roleIds != null) {
            for (String roleId : roleIds) {
                if (isBlank(roleId)) continue;
                RoleEntity role = roleRepository.findById(roleId.trim())
                        .orElseThrow(() -> new AuthService.AuthException("ROLE_NOT_FOUND",
                                "role not found: " + roleId));
                if (!java.util.Objects.equals(role.getTenantId(), tenantId)) {
                    throw new AuthService.AuthException("forbidden",
                            "role '" + roleId + "' belongs to tenant '" + role.getTenantId()
                                    + "', cannot assign within tenant '" + tenantId + "'");
                }
            }
        }
        userTenantRoleRepository.findByUserIdAndTenantId(userId, tenantId)
                .forEach(userTenantRoleRepository::delete);
        userTenantRoleRepository.flush();
        if (roleIds == null) return;
        for (String roleId : roleIds) {
            if (isBlank(roleId)) continue;
            UserTenantRoleEntity utr = new UserTenantRoleEntity();
            utr.setUserId(userId);
            utr.setTenantId(tenantId);
            utr.setRoleId(roleId.trim());
            userTenantRoleRepository.save(utr);
        }
    }

    public List<UserTenantRoleView> listUserTenantRoles(String userId) {
        requireUserAccessible(userId);
        AuthPrincipal p = SecurityContextHolder.current();
        boolean systemAdmin = isSystemAdmin(p);
        return userTenantRoleRepository.findByUserId(userId).stream()
                // 租户管理员只看自己租户的绑定行,不要泄露目标用户在别租户的角色
                .filter(e -> systemAdmin || e.getTenantId().equals(p.tenantId()))
                .map(e -> new UserTenantRoleView(e.getUserId(), e.getTenantId(), e.getRoleId(), e.getGrantedAt()))
                .toList();
    }

    // ── Permissions ────────────────────────────────────────────────────────

    public List<PermissionView> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionView(p.getCode(), p.getCategory(), p.getDescription()))
                .toList();
    }

    public List<RolePermissionView> listRolePermissions(String roleId) {
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(rp -> new RolePermissionView(rp.getRoleId(), rp.getPermissionCode()))
                .toList();
    }

    public List<UserPermissionView> listUserPermissions(String userId, String tenantId) {
        return userPermissionRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .map(up -> new UserPermissionView(up.getUserId(), up.getTenantId(),
                        up.getPermissionCode(), up.getEffect(), up.getGrantedAt()))
                .toList();
    }

    /** 单条用户级覆盖 upsert。effect=GRANT/DENY。 */
    @Transactional
    public UserPermissionView upsertUserPermission(String userId, String tenantId,
                                                    String permissionCode, String effect) {
        if (isBlank(permissionCode) || isBlank(effect)) {
            throw new AuthService.AuthException("INVALID_INPUT", "permissionCode/effect required");
        }
        String upper = effect.trim().toUpperCase();
        if (!upper.equals("GRANT") && !upper.equals("DENY")) {
            throw new AuthService.AuthException("INVALID_INPUT", "effect must be GRANT or DENY");
        }
        UserPermissionEntity entity = new UserPermissionEntity();
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setPermissionCode(permissionCode.trim());
        entity.setEffect(upper);
        UserPermissionEntity saved = userPermissionRepository.save(entity);
        return new UserPermissionView(saved.getUserId(), saved.getTenantId(),
                saved.getPermissionCode(), saved.getEffect(), saved.getGrantedAt());
    }

    @Transactional
    public void deleteUserPermission(String userId, String tenantId, String permissionCode) {
        userPermissionRepository.deleteById(new UserPermissionEntity.Pk(userId, tenantId, permissionCode));
    }

    // ── OAuth Clients ──────────────────────────────────────────────────────

    public List<OauthClientView> listOauthClients() {
        AuthPrincipal p = SecurityContextHolder.current();
        return oauthClientRepository.findAll().stream()
                .filter(e -> isSystemAdmin(p) || java.util.Objects.equals(e.getTenantId(), p.tenantId()))
                .map(this::toOauthClientView)
                .toList();
    }

    /** 创建 client。生成并返回明文 secret(只此一次)。 */
    @Transactional
    public OauthClientCreated createOauthClient(OauthClientCommand cmd) {
        if (cmd == null || isBlank(cmd.displayName())) {
            throw new AuthService.AuthException("INVALID_INPUT", "displayName is required");
        }
        String clientId = isBlank(cmd.clientId())
                ? "cli_" + Long.toString(System.currentTimeMillis(), 36) + "_" + randomToken(8)
                : cmd.clientId().trim();
        if (oauthClientRepository.findById(clientId).isPresent()) {
            throw new AuthService.AuthException("CLIENT_ID_TAKEN", "client_id already exists");
        }
        AuthPrincipal p = SecurityContextHolder.current();
        String secret = randomToken(32);
        OauthClientEntity entity = new OauthClientEntity();
        entity.setClientId(clientId);
        entity.setClientSecretHash(BCRYPT.encode(secret));
        entity.setDisplayName(cmd.displayName().trim());
        entity.setRedirectUris(cmd.redirectUris() == null ? "[]" : cmd.redirectUris());
        entity.setScopes(cmd.scopes() == null ? "[]" : cmd.scopes());
        entity.setStatus(isBlank(cmd.status()) ? "ACTIVE" : cmd.status().trim().toUpperCase());
        entity.setOwnerUserId(p.anonymous() ? blankToNull(cmd.ownerUserId()) : p.userId());
        entity.setDescription(blankToNull(cmd.description()));
        // 租户归属:新 client 强制落到 principal 的租户,防止租户管理员越权创建到别家。
        entity.setTenantId(p.anonymous() ? "system" : p.tenantId());
        OauthClientEntity saved = oauthClientRepository.save(entity);
        return new OauthClientCreated(toOauthClientView(saved), secret);
    }

    @Transactional
    public OauthClientView updateOauthClient(String clientId, OauthClientCommand cmd) {
        OauthClientEntity entity = oauthClientRepository.findById(clientId)
                .orElseThrow(() -> new AuthService.AuthException("CLIENT_NOT_FOUND", "client not found"));
        AuthPrincipal p = SecurityContextHolder.current();
        if (!isSystemAdmin(p) && !java.util.Objects.equals(entity.getTenantId(), p.tenantId())) {
            throw new AuthService.AuthException("forbidden", "client belongs to another tenant");
        }
        if (cmd.displayName() != null) entity.setDisplayName(cmd.displayName().trim());
        if (cmd.redirectUris() != null) entity.setRedirectUris(cmd.redirectUris());
        if (cmd.scopes() != null) entity.setScopes(cmd.scopes());
        if (cmd.status() != null) entity.setStatus(cmd.status().trim().toUpperCase());
        if (cmd.description() != null) entity.setDescription(blankToNull(cmd.description()));
        return toOauthClientView(oauthClientRepository.save(entity));
    }

    /** 旋转 client_secret,作废旧的。返回新明文 secret。 */
    @Transactional
    public String rotateOauthClientSecret(String clientId) {
        OauthClientEntity entity = oauthClientRepository.findById(clientId)
                .orElseThrow(() -> new AuthService.AuthException("CLIENT_NOT_FOUND", "client not found"));
        AuthPrincipal p = SecurityContextHolder.current();
        if (!isSystemAdmin(p) && !java.util.Objects.equals(entity.getTenantId(), p.tenantId())) {
            throw new AuthService.AuthException("forbidden", "client belongs to another tenant");
        }
        String secret = randomToken(32);
        entity.setClientSecretHash(BCRYPT.encode(secret));
        oauthClientRepository.save(entity);
        return secret;
    }

    @Transactional
    public void deleteOauthClient(String clientId) {
        OauthClientEntity entity = oauthClientRepository.findById(clientId).orElse(null);
        if (entity == null) return;
        AuthPrincipal p = SecurityContextHolder.current();
        if (!isSystemAdmin(p) && !java.util.Objects.equals(entity.getTenantId(), p.tenantId())) {
            throw new AuthService.AuthException("forbidden", "client belongs to another tenant");
        }
        oauthClientRepository.delete(entity);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private UserView toUserView(AppUserEntity e) {
        return new UserView(e.getId(), e.getUsername(), e.getEmail(), e.getDisplayName(),
                e.getStatus(), e.getPreferredTenantId(), e.isPasswordMustChange(),
                e.getLastLoginAt(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private TenantView toTenantView(TenantEntity e) {
        return new TenantView(e.getId(), e.getCode(), e.getName(), e.getKind(),
                e.getStatus(), e.getDescription(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private RoleView toRoleView(RoleEntity e) {
        return new RoleView(e.getId(), e.getTenantId(), e.getCode(), e.getName(),
                e.getDescription(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private OauthClientView toOauthClientView(OauthClientEntity e) {
        return new OauthClientView(e.getClientId(), e.getDisplayName(), e.getRedirectUris(),
                e.getScopes(), e.getStatus(), e.getOwnerUserId(), e.getDescription(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    /** 安全字母表(去掉 0/O/1/l/I 这些视觉混淆字符)。 */
    private static final char[] PWD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%^&*-_=+".toCharArray();

    private static String generateTempPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(PWD_ALPHABET[RNG.nextInt(PWD_ALPHABET.length)]);
        return sb.toString();
    }

    private static String randomToken(int byteLength) {
        byte[] buf = new byte[byteLength];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // ── Records exposed to controller ──────────────────────────────────────

    public record UserView(String id, String username, String email, String displayName,
                           String status, String preferredTenantId, boolean passwordMustChange,
                           Instant lastLoginAt, Instant createdAt, Instant updatedAt) {}
    public record CreatedUser(UserView user, String temporaryPassword) {}
    public record UserCreateCommand(String id, String username, String email, String displayName,
                                     String preferredTenantId, String initialPassword) {}
    public record UserUpdateCommand(String displayName, String email, String status, String preferredTenantId) {}

    public record TenantView(String id, String code, String name, String kind, String status,
                             String description, Instant createdAt, Instant updatedAt) {}
    public record TenantCommand(String id, String code, String name, String kind, String status,
                                 String description) {}

    public record RoleView(String id, String tenantId, String code, String name, String description,
                           Instant createdAt, Instant updatedAt) {}
    public record RoleCommand(String id, String tenantId, String code, String name, String description) {}

    public record PermissionView(String code, String category, String description) {}
    public record RolePermissionView(String roleId, String permissionCode) {}

    public record UserTenantRoleView(String userId, String tenantId, String roleId, Instant grantedAt) {}
    public record UserPermissionView(String userId, String tenantId, String permissionCode,
                                      String effect, Instant grantedAt) {}

    public record OauthClientView(String clientId, String displayName, String redirectUris,
                                   String scopes, String status, String ownerUserId, String description,
                                   Instant createdAt, Instant updatedAt) {}
    public record OauthClientCommand(String clientId, String displayName, String redirectUris,
                                      String scopes, String status, String ownerUserId, String description) {}
    public record OauthClientCreated(OauthClientView client, String clientSecret) {}
}
