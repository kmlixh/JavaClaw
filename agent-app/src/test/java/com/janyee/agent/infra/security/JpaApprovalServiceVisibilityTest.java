package com.janyee.agent.infra.security;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import com.janyee.agent.infra.persistence.entity.ApprovalRequestEntity;
import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.repository.ApprovalRequestRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.security.ApprovalRequestView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 ApprovalController 的可见性:
 *   - sysadmin 看见所有 approval(跨租户)
 *   - 租户管理员只看自己租户 session 关联的 approval
 *   - 普通用户只看自己 owner 的 session 关联的 approval
 *   - dangling sessionId(关联 session 已删) → 只 sysadmin 可见,其他人保守拒绝
 *
 * 修这条之前 /api/approvals 完全没权限校验,任何登录用户都能跨租户读/批/拒。
 */
class JpaApprovalServiceVisibilityTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clear();
    }

    private ApprovalRequestEntity approval(String id, String sessionId) {
        ApprovalRequestEntity a = new ApprovalRequestEntity();
        a.setId(id);
        a.setSessionId(sessionId);
        a.setRunId("run-" + id);
        a.setAgentId("dev-agent");
        a.setToolName("shell.exec");
        a.setStatus("PENDING");
        // ApprovalRequestEntity 的 createdAt 通过 @PrePersist 由 JPA 自动写入,
        // 测试里 repo 是 mock 不会触发,只能反射手动塞入避免 sort 时 NPE。
        try {
            java.lang.reflect.Field f = ApprovalRequestEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(a, java.time.Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return a;
    }

    private SessionEntity session(String id, String tenantId, String userId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setUserId(userId);
        s.setAgentId("dev-agent");
        s.setStatus("ACTIVE");
        return s;
    }

    private AuthPrincipal sysadmin() {
        return new AuthPrincipal("admin", "system", "system-default",
                Set.of("session.read.all"), false);
    }

    private AuthPrincipal tenantAdmin(String tenantId) {
        return new AuthPrincipal("ext:" + tenantId + ":admin", tenantId, tenantId,
                Set.of("session.read.tenant"), false);
    }

    private AuthPrincipal regularUser(String tenantId, String userId) {
        return new AuthPrincipal(userId, tenantId, tenantId,
                Set.of("session.read.own"), false);
    }

    @Test
    void sysadminListsAllApprovalsAcrossTenants() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity a1 = approval("a1", "sess-xmap");
        ApprovalRequestEntity a2 = approval("a2", "sess-other");
        when(approvalRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a1, a2));
        when(sessionRepo.findById("sess-xmap")).thenReturn(Optional.of(session("sess-xmap", "xmap", "ext:xmap:1")));
        when(sessionRepo.findById("sess-other")).thenReturn(Optional.of(session("sess-other", "other", "ext:other:1")));

        SecurityContextHolder.setCurrent(sysadmin());
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        List<ApprovalRequestView> views = svc.listRequests(null, null, null);
        assertEquals(2, views.size(), "sysadmin 必须能跨租户看到所有 approval");
    }

    @Test
    void tenantAdminListsOnlyOwnTenantApprovals() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity a1 = approval("a1", "sess-xmap");
        ApprovalRequestEntity a2 = approval("a2", "sess-other");
        when(approvalRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a1, a2));
        when(sessionRepo.findById("sess-xmap")).thenReturn(Optional.of(session("sess-xmap", "xmap", "ext:xmap:1")));
        when(sessionRepo.findById("sess-other")).thenReturn(Optional.of(session("sess-other", "other", "ext:other:1")));

        SecurityContextHolder.setCurrent(tenantAdmin("xmap"));
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        List<ApprovalRequestView> views = svc.listRequests(null, null, null);
        assertEquals(1, views.size(), "租户管理员只能看自己租户");
        assertEquals("a1", views.get(0).approvalRequestId());
    }

    @Test
    void regularUserListsOnlyOwnSessionApprovals() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity mine = approval("a1", "sess-mine");
        ApprovalRequestEntity colleague = approval("a2", "sess-colleague");
        when(approvalRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(mine, colleague));
        when(sessionRepo.findById("sess-mine"))
                .thenReturn(Optional.of(session("sess-mine", "xmap", "ext:xmap:1")));
        when(sessionRepo.findById("sess-colleague"))
                .thenReturn(Optional.of(session("sess-colleague", "xmap", "ext:xmap:2")));

        SecurityContextHolder.setCurrent(regularUser("xmap", "ext:xmap:1"));
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        List<ApprovalRequestView> views = svc.listRequests(null, null, null);
        assertEquals(1, views.size(),
                "普通用户只能看自己 owner 的 session 的 approval,不能看同租户同事的");
        assertEquals("a1", views.get(0).approvalRequestId());
    }

    @Test
    void danglingSessionIsHiddenFromNonSysadmin() {
        // approval 引用的 sessionId 在 session 表里查不到(已删 / 数据脏)。
        // 之前 /api/approvals 完全不校验,这种 dangling approval 任何人都能看;现在保守拒绝
        // 给非 sysadmin。
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity dangling = approval("a1", "sess-deleted");
        when(approvalRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(dangling));
        when(sessionRepo.findById("sess-deleted")).thenReturn(Optional.empty());

        SecurityContextHolder.setCurrent(regularUser("xmap", "ext:xmap:1"));
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        List<ApprovalRequestView> views = svc.listRequests(null, null, null);
        assertTrue(views.isEmpty(),
                "session 已删,普通用户不应再看到这条 approval(防止信息泄漏)");
    }

    @Test
    void danglingSessionStillVisibleToSysadmin() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity dangling = approval("a1", "sess-deleted");
        when(approvalRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(dangling));
        when(sessionRepo.findById("sess-deleted")).thenReturn(Optional.empty());

        SecurityContextHolder.setCurrent(sysadmin());
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        List<ApprovalRequestView> views = svc.listRequests(null, null, null);
        assertEquals(1, views.size(),
                "sysadmin 仍能看 dangling approval 以便清理");
    }

    @Test
    void approveRejectRequiresAccess() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity entity = approval("a1", "sess-other");
        when(approvalRepo.findById("a1")).thenReturn(Optional.of(entity));
        when(sessionRepo.findById("sess-other"))
                .thenReturn(Optional.of(session("sess-other", "other", "ext:other:1")));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecurityContextHolder.setCurrent(regularUser("xmap", "ext:xmap:1"));
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        SecurityException ex = assertThrows(SecurityException.class,
                () -> svc.approve("a1"),
                "其他租户用户不应能批准/拒绝当前 approval");
        assertTrue(ex.getMessage().contains("a1"));
        assertThrows(SecurityException.class, () -> svc.reject("a1"));
    }

    @Test
    void getRequestRequiresAccess() {
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ApprovalRequestEntity entity = approval("a1", "sess-other");
        when(approvalRepo.findById("a1")).thenReturn(Optional.of(entity));
        when(sessionRepo.findById("sess-other"))
                .thenReturn(Optional.of(session("sess-other", "other", "ext:other:1")));

        SecurityContextHolder.setCurrent(regularUser("xmap", "ext:xmap:1"));
        JpaApprovalService svc = new JpaApprovalService(approvalRepo, sessionRepo);
        assertThrows(SecurityException.class, () -> svc.getRequest("a1"));
    }
}
