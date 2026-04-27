package com.janyee.agent.web.controller;

import com.janyee.agent.infra.admin.AdminUserManagementService;
import com.janyee.agent.infra.admin.AdminUserManagementService.OauthClientCommand;
import com.janyee.agent.infra.admin.AdminUserManagementService.RoleCommand;
import com.janyee.agent.infra.admin.AdminUserManagementService.TenantCommand;
import com.janyee.agent.infra.admin.AdminUserManagementService.UserCreateCommand;
import com.janyee.agent.infra.admin.AdminUserManagementService.UserUpdateCommand;
import com.janyee.agent.infra.auth.PermissionGate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台管理:用户/租户/角色/权限/OAuth 客户端 的 REST 入口。
 * 每个写操作都先 PermissionGate.require(...);读列表也带上对应 read 权限,避免普通 user 拉到全表。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserManagementController {

    private final AdminUserManagementService service;

    public AdminUserManagementController(AdminUserManagementService service) {
        this.service = service;
    }

    // ── Users ──────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<AdminUserManagementService.UserView> listUsers() {
        PermissionGate.require("user.manage");
        return service.listUsers();
    }

    @GetMapping("/users/{id}")
    public AdminUserManagementService.UserView getUser(@PathVariable("id") String id) {
        PermissionGate.require("user.manage");
        return service.getUser(id);
    }

    @PostMapping("/users")
    public AdminUserManagementService.CreatedUser createUser(@RequestBody UserCreateCommand cmd) {
        PermissionGate.require("user.manage");
        return service.createUser(cmd);
    }

    @PutMapping("/users/{id}")
    public AdminUserManagementService.UserView updateUser(@PathVariable("id") String id,
                                                          @RequestBody UserUpdateCommand cmd) {
        PermissionGate.require("user.manage");
        return service.updateUser(id, cmd);
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> resetPassword(@PathVariable("id") String id) {
        PermissionGate.require("user.manage");
        return Map.of("temporaryPassword", service.resetPassword(id));
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(@PathVariable("id") String id) {
        PermissionGate.require("user.manage");
        service.deleteUser(id);
    }

    /** 批量导入用户。请求体是 UserCreateCommand 数组,每条独立处理失败不阻塞。 */
    @PostMapping("/users/import")
    public List<AdminUserManagementService.ImportResult> importUsers(
            @RequestBody List<UserCreateCommand> rows) {
        PermissionGate.require("user.manage");
        return service.importUsers(rows);
    }

    /** 给用户在某租户分配角色集合(覆盖式)。 */
    @PostMapping("/users/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable("id") String id,
                                            @RequestBody AssignRolesRequest body) {
        PermissionGate.require("user.manage");
        service.assignUserRoles(id, body.tenantId(), body.roleIds());
        return Map.of("ok", true);
    }

    @GetMapping("/users/{id}/tenant-roles")
    public List<AdminUserManagementService.UserTenantRoleView> listUserTenantRoles(@PathVariable("id") String id) {
        PermissionGate.require("user.manage");
        return service.listUserTenantRoles(id);
    }

    public record AssignRolesRequest(String tenantId, List<String> roleIds) {}

    // ── Tenants ────────────────────────────────────────────────────────────

    @GetMapping("/tenants")
    public List<AdminUserManagementService.TenantView> listTenants() {
        PermissionGate.require("tenant.manage");
        return service.listTenants();
    }

    @PostMapping("/tenants")
    public AdminUserManagementService.TenantView saveTenant(@RequestBody TenantCommand cmd) {
        PermissionGate.require("tenant.manage");
        return service.saveTenant(cmd);
    }

    @DeleteMapping("/tenants/{id}")
    public void deleteTenant(@PathVariable("id") String id) {
        PermissionGate.require("tenant.manage");
        service.deleteTenant(id);
    }

    // ── Roles ──────────────────────────────────────────────────────────────

    @GetMapping("/roles")
    public List<AdminUserManagementService.RoleView> listRoles(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        PermissionGate.require("permission.manage");
        return service.listRoles(tenantId);
    }

    @PostMapping("/roles")
    public AdminUserManagementService.RoleView saveRole(@RequestBody RoleCommand cmd) {
        PermissionGate.require("permission.manage");
        return service.saveRole(cmd);
    }

    @DeleteMapping("/roles/{id}")
    public void deleteRole(@PathVariable("id") String id) {
        PermissionGate.require("permission.manage");
        service.deleteRole(id);
    }

    @GetMapping("/roles/{id}/permissions")
    public List<AdminUserManagementService.RolePermissionView> listRolePermissions(@PathVariable("id") String id) {
        PermissionGate.require("permission.manage");
        return service.listRolePermissions(id);
    }

    @PutMapping("/roles/{id}/permissions")
    public Map<String, Object> updateRolePermissions(@PathVariable("id") String id,
                                                      @RequestBody UpdateRolePermissionsRequest body) {
        PermissionGate.require("permission.manage");
        return Map.of("permissions", service.updateRolePermissions(id, body.permissionCodes()));
    }

    public record UpdateRolePermissionsRequest(List<String> permissionCodes) {}

    // ── Permissions ────────────────────────────────────────────────────────

    @GetMapping("/permissions")
    public List<AdminUserManagementService.PermissionView> listPermissions() {
        PermissionGate.require("permission.manage");
        return service.listPermissions();
    }

    // ── User-level permission overrides ────────────────────────────────────

    @GetMapping("/user-permissions")
    public List<AdminUserManagementService.UserPermissionView> listUserPermissions(
            @RequestParam("userId") String userId,
            @RequestParam("tenantId") String tenantId) {
        PermissionGate.require("permission.manage");
        return service.listUserPermissions(userId, tenantId);
    }

    @PostMapping("/user-permissions")
    public AdminUserManagementService.UserPermissionView upsertUserPermission(
            @RequestBody UpsertUserPermissionRequest body) {
        PermissionGate.require("permission.manage");
        return service.upsertUserPermission(body.userId(), body.tenantId(),
                body.permissionCode(), body.effect());
    }

    @DeleteMapping("/user-permissions")
    public void deleteUserPermission(
            @RequestParam("userId") String userId,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("permissionCode") String permissionCode) {
        PermissionGate.require("permission.manage");
        service.deleteUserPermission(userId, tenantId, permissionCode);
    }

    public record UpsertUserPermissionRequest(String userId, String tenantId,
                                                String permissionCode, String effect) {}

    // ── OAuth clients ──────────────────────────────────────────────────────

    @GetMapping("/oauth-clients")
    public List<AdminUserManagementService.OauthClientView> listOauthClients() {
        PermissionGate.require("oauth.client.manage");
        return service.listOauthClients();
    }

    @PostMapping("/oauth-clients")
    public AdminUserManagementService.OauthClientCreated createOauthClient(@RequestBody OauthClientCommand cmd) {
        PermissionGate.require("oauth.client.manage");
        return service.createOauthClient(cmd);
    }

    @PutMapping("/oauth-clients/{clientId}")
    public AdminUserManagementService.OauthClientView updateOauthClient(
            @PathVariable("clientId") String clientId,
            @RequestBody OauthClientCommand cmd) {
        PermissionGate.require("oauth.client.manage");
        return service.updateOauthClient(clientId, cmd);
    }

    @PostMapping("/oauth-clients/{clientId}/rotate-secret")
    public Map<String, String> rotateSecret(@PathVariable("clientId") String clientId) {
        PermissionGate.require("oauth.client.manage");
        return Map.of("clientSecret", service.rotateOauthClientSecret(clientId));
    }

    @DeleteMapping("/oauth-clients/{clientId}")
    public void deleteOauthClient(@PathVariable("clientId") String clientId) {
        PermissionGate.require("oauth.client.manage");
        service.deleteOauthClient(clientId);
    }
}
