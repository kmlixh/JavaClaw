package com.janyee.agent.infra.run;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.RunSummaryView;
import com.janyee.agent.runtime.run.LiveRunRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端核对"DB 挂着 in-progress 但进程里没跑"这类僵尸 run 的修正链路：
 * <ol>
 *   <li>{@link StaleRunReconciler#reconcileIfStale} 对 registry 不认识的 in-progress run
 *       把 DB 状态改成 FAILED；</li>
 *   <li>{@link AgentQueryService#getRun} 返回的视图跟着变 FAILED —— 前端刷新后不会再卡在"执行中"；</li>
 *   <li>{@link AgentQueryService#findActiveRun} 对僵尸 run 返回 empty，避免前端把它当成 "active"；</li>
 *   <li>对真正登记在 {@link LiveRunRegistry} 的 in-progress run 什么都不做。</li>
 * </ol>
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaleRunReconcilerTest {

    @Autowired
    private RunRecordRepository runRecordRepository;

    @Autowired
    private StaleRunReconciler staleRunReconciler;

    @Autowired
    private LiveRunRegistry liveRunRegistry;

    @Autowired
    private AgentQueryService agentQueryService;

    private String runId;
    private String sessionId;

    @BeforeEach
    void seedRun() {
        runId = "stale-run-" + UUID.randomUUID();
        sessionId = "stale-sess-" + UUID.randomUUID();
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(runId);
        entity.setSessionId(sessionId);
        entity.setAgentId("dev-agent");
        entity.setUserId("junit");
        entity.setStatus(RunStatus.MODEL_RUNNING.name());
        entity.setDetail("simulated in-progress run");
        runRecordRepository.save(entity);
    }

    @AfterEach
    void cleanup() {
        liveRunRegistry.unregister(runId);
        runRecordRepository.findById(runId).ifPresent(runRecordRepository::delete);
    }

    @Test
    void reconcileMarksUnregisteredInProgressRunAsFailed() {
        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.MODEL_RUNNING.name(), entity.getStatus());
        assertFalse(liveRunRegistry.isActive(runId), "registry should not know about the seeded run");

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertTrue(changed, "reconciler should mark the stale run FAILED");
        assertEquals(RunStatus.FAILED.name(), entity.getStatus());
        assertNotNull(entity.getDetail());
        assertTrue(entity.getDetail().contains("auto-terminated"),
                "detail should explain the reason. actual=" + entity.getDetail());

        RunRecordEntity persisted = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), persisted.getStatus(),
                "DB row must be rewritten so next query sees FAILED immediately");
    }

    @Test
    void reconcileLeavesRunningRunAlone() {
        liveRunRegistry.register(runId);
        try {
            RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
            boolean changed = staleRunReconciler.reconcileIfStale(entity);
            assertFalse(changed, "registered run must not be reconciled");
            assertEquals(RunStatus.MODEL_RUNNING.name(),
                    runRecordRepository.findById(runId).orElseThrow().getStatus(),
                    "DB status must remain MODEL_RUNNING");
        } finally {
            liveRunRegistry.unregister(runId);
        }
    }

    @Test
    void reconcileLeavesTerminalRunAlone() {
        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        entity.setStatus(RunStatus.COMPLETED.name());
        runRecordRepository.save(entity);

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertFalse(changed, "COMPLETED is terminal, reconciler should skip");
        assertEquals(RunStatus.COMPLETED.name(),
                runRecordRepository.findById(runId).orElseThrow().getStatus());
    }

    @Test
    void reconcileLeavesWaitingApprovalAlone() {
        // WAITING_APPROVAL 是合法的长生命期暂停状态，不应该被当成僵尸
        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        entity.setStatus(RunStatus.WAITING_APPROVAL.name());
        runRecordRepository.save(entity);

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertFalse(changed, "WAITING_APPROVAL should not be reconciled even without registry entry");
        assertEquals(RunStatus.WAITING_APPROVAL.name(),
                runRecordRepository.findById(runId).orElseThrow().getStatus());
    }

    @Test
    void getRunReturnsFailedStatusAfterReconcile() {
        // DB 里是 MODEL_RUNNING，registry 里没登记 → agentQueryService.getRun 应返回 FAILED
        RunDetailView view = agentQueryService.getRun(runId);
        assertEquals(RunStatus.FAILED.name(), view.status(),
                "frontend must see the corrected FAILED status, not the stale MODEL_RUNNING");
        assertNotNull(view.detail());
        assertTrue(view.detail().contains("auto-terminated"),
                "detail 应告知前端这是自动终止。actual=" + view.detail());
    }

    @Test
    void findActiveRunSkipsStaleInProgressRun() {
        // DB 显示该 session 有一条 in-progress run，但 registry 未登记 → findActiveRun 应返回 empty
        Optional<RunDetailView> active = agentQueryService.findActiveRun(sessionId);
        assertTrue(active.isEmpty(),
                "stale run should not be reported as active; otherwise the frontend stays stuck in running state");

        // 侧面验证：reconcile 应已经把 DB 刷成 FAILED
        RunRecordEntity persisted = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), persisted.getStatus());
    }

    @Test
    void findActiveRunKeepsLiveInProgressRun() {
        liveRunRegistry.register(runId);
        try {
            Optional<RunDetailView> active = agentQueryService.findActiveRun(sessionId);
            assertTrue(active.isPresent(), "真正在跑的 run 必须被 findActiveRun 返回");
            assertEquals(RunStatus.MODEL_RUNNING.name(), active.get().status());
        } finally {
            liveRunRegistry.unregister(runId);
        }
    }

    @Test
    void liveRunRegistryTracksRegisterAndUnregister() {
        String adhoc = "adhoc-" + UUID.randomUUID();
        assertFalse(liveRunRegistry.isActive(adhoc));
        liveRunRegistry.register(adhoc);
        assertTrue(liveRunRegistry.isActive(adhoc));
        assertTrue(liveRunRegistry.activeRunIds().contains(adhoc));
        liveRunRegistry.unregister(adhoc);
        assertFalse(liveRunRegistry.isActive(adhoc));

        // 边界：null / blank 不应抛异常也不应登记
        liveRunRegistry.register(null);
        liveRunRegistry.register(" ");
        assertFalse(liveRunRegistry.isActive(null));
        assertFalse(liveRunRegistry.isActive(""));
    }

    @Test
    void listRunsBySessionReconcilesEachStaleRun() {
        // seedRun 留下 MODEL_RUNNING 的 runId；再插入一条已经 COMPLETED 的 run 做对照
        String completedRunId = "stale-completed-" + UUID.randomUUID();
        RunRecordEntity completed = new RunRecordEntity();
        completed.setId(completedRunId);
        completed.setSessionId(sessionId);
        completed.setAgentId("dev-agent");
        completed.setUserId("junit");
        completed.setStatus(RunStatus.COMPLETED.name());
        completed.setDetail("normal completion");
        runRecordRepository.save(completed);

        try {
            List<RunSummaryView> runs = agentQueryService.listRunsBySession(sessionId);
            assertEquals(2, runs.size(), "session 下应该有两条 run");

            RunSummaryView staleView = runs.stream()
                    .filter(r -> runId.equals(r.runId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RunStatus.FAILED.name(), staleView.status(),
                    "listRunsBySession 返回的僵尸 run 必须已经被修正为 FAILED —— 前端就是靠这个判断 run 是否 terminal");
            assertTrue(staleView.detail().contains("auto-terminated"),
                    "detail 应告知原因: " + staleView.detail());

            RunSummaryView completedView = runs.stream()
                    .filter(r -> completedRunId.equals(r.runId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RunStatus.COMPLETED.name(), completedView.status(),
                    "原本就 COMPLETED 的 run 不应被改动");
        } finally {
            runRecordRepository.findById(completedRunId).ifPresent(runRecordRepository::delete);
        }
    }

    @Test
    void reconcileHandlesAllInProgressStatuses() {
        // 所有 in-progress 状态都应走修正
        for (RunStatus status : new RunStatus[]{
                RunStatus.RECEIVED,
                RunStatus.CONTEXT_BUILT,
                RunStatus.MODEL_RUNNING,
                RunStatus.TOOL_REQUESTED,
                RunStatus.TOOL_EXECUTING,
                RunStatus.TOOL_RESULT_APPENDED
        }) {
            RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
            entity.setStatus(status.name());
            entity.setDetail("reset-for-" + status.name());
            runRecordRepository.save(entity);

            boolean changed = staleRunReconciler.reconcileIfStale(entity);
            assertTrue(changed, "in-progress 状态 " + status + " 应被修正");
            assertEquals(RunStatus.FAILED.name(), entity.getStatus());
        }
    }
}
