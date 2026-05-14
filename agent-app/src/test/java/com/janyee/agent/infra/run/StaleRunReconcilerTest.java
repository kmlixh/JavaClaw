package com.janyee.agent.infra.run;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.query.RunDetailView;
import com.janyee.agent.runtime.query.RunSummaryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaleRunReconciler 新行为(去掉 LiveRunRegistry 判死之后):
 * <ul>
 *   <li>只有 updated_at &gt; 60min 的 in-progress run 才被改 FAILED;</li>
 *   <li>读路径(getRun / listRunsBySession / findActiveRun) <b>不</b>再做 reconcile 副作用,
 *       直接返回 DB 当前状态;</li>
 *   <li>终态 run 永远不动;</li>
 *   <li>WAITING_APPROVAL 是合法长生命期挂起,永远不动。</li>
 * </ul>
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
        runRecordRepository.findById(runId).ifPresent(runRecordRepository::delete);
    }

    @Test
    void reconcileLeavesRecentlyUpdatedRunAlone() {
        // 刚 save 出来 updated_at = 现在,远在 60min 阈值之内 → 不杀
        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.MODEL_RUNNING.name(), entity.getStatus());

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertFalse(changed, "recently updated run must not be touched by reconciler");
        assertEquals(RunStatus.MODEL_RUNNING.name(),
                runRecordRepository.findById(runId).orElseThrow().getStatus(),
                "DB row must stay MODEL_RUNNING");
    }

    @Test
    void reconcileMarksHardStaleRunFailed() {
        // 用反射 / 直接 UPDATE 把 updated_at 推到 61 分钟前。@PreUpdate 会在 save 时把它顶回现在,
        // 所以必须绕过 save —— 这里走原生查询。
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(61));
        runRecordRepository.findById(runId).orElseThrow();
        runRecordRepository.forceUpdatedAtForTest(runId, longAgo);

        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        assertTrue(Duration.between(entity.getUpdatedAt(), Instant.now())
                        .compareTo(Duration.ofMinutes(60)) > 0,
                "test setup must produce a >60min-old updated_at");

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertTrue(changed, "hard-stale (>60min) run should be marked FAILED");
        assertEquals(RunStatus.FAILED.name(), entity.getStatus());
        assertNotNull(entity.getDetail());
        assertTrue(entity.getDetail().contains("auto-terminated"),
                "detail should explain reason. actual=" + entity.getDetail());
        assertTrue(entity.getDetail().contains("60 min"),
                "detail should mention the 60min threshold");

        RunRecordEntity persisted = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), persisted.getStatus(),
                "DB row must be rewritten so next query sees FAILED immediately");
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
        RunRecordEntity entity = runRecordRepository.findById(runId).orElseThrow();
        entity.setStatus(RunStatus.WAITING_APPROVAL.name());
        runRecordRepository.save(entity);

        boolean changed = staleRunReconciler.reconcileIfStale(entity);
        assertFalse(changed, "WAITING_APPROVAL is a legitimate long-lived pause, never reconcile");
        assertEquals(RunStatus.WAITING_APPROVAL.name(),
                runRecordRepository.findById(runId).orElseThrow().getStatus());
    }

    @Test
    void getRunReturnsDbStatusWithoutSideEffects() {
        // 读 DB 不再 reconcile —— 即便看到 in-progress 也不动它。前端拿到原样返回。
        RunDetailView view = agentQueryService.getRun(runId);
        assertEquals(RunStatus.MODEL_RUNNING.name(), view.status(),
                "getRun must not silently mutate run state");
        RunRecordEntity persisted = runRecordRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.MODEL_RUNNING.name(), persisted.getStatus(),
                "reading must not trigger any DB write");
    }

    @Test
    void findActiveRunReturnsInProgressRunAsIs() {
        // session 下有一条 in-progress,findActiveRun 应直接返回。不再用 registry 过滤。
        Optional<RunDetailView> active = agentQueryService.findActiveRun(sessionId);
        assertTrue(active.isPresent(), "in-progress run should be reported as active");
        assertEquals(RunStatus.MODEL_RUNNING.name(), active.get().status());
    }

    @Test
    void listRunsBySessionReturnsDbStatusAsIs() {
        // 多插一条已 COMPLETED 的 run 做对照
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

            RunSummaryView modelRunningView = runs.stream()
                    .filter(r -> runId.equals(r.runId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RunStatus.MODEL_RUNNING.name(), modelRunningView.status(),
                    "listRunsBySession 应原样返回 DB 状态,不再做 reconcile 副作用");

            RunSummaryView completedView = runs.stream()
                    .filter(r -> completedRunId.equals(r.runId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RunStatus.COMPLETED.name(), completedView.status());
        } finally {
            runRecordRepository.findById(completedRunId).ifPresent(runRecordRepository::delete);
        }
    }

    @Test
    void reconcileHandlesAllInProgressStatuses() {
        // 所有 in-progress 状态都应在 hardStale 条件下被修正
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
            runRecordRepository.forceUpdatedAtForTest(runId, Instant.now().minus(Duration.ofMinutes(61)));

            RunRecordEntity reloaded = runRecordRepository.findById(runId).orElseThrow();
            boolean changed = staleRunReconciler.reconcileIfStale(reloaded);
            assertTrue(changed, "hard-stale in-progress 状态 " + status + " 应被修正");
            assertEquals(RunStatus.FAILED.name(), reloaded.getStatus());
        }
    }
}
