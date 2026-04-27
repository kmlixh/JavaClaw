package com.janyee.agent.infra.run;

import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.run.LiveRunRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 对"DB 里还挂着 in-progress 但服务端实际没在跑"的 run 做补救：
 * <ol>
 *   <li>把 DB 状态写成 {@link RunStatus#FAILED}，detail 注明自动终止的原因；</li>
 *   <li>修改传入的 {@link RunRecordEntity} 以便调用方把更新后的状态一起返回给前端，
 *       避免"先读到 MODEL_RUNNING，下一次请求又读到 FAILED"这种闪烁。</li>
 * </ol>
 *
 * <p>Reconcile 只对当前没有锁持有线程的 run 生效 —— 真正在跑的 run 会被 {@link LiveRunRegistry}
 * 登记，不会被误判。</p>
 *
 * <p>不处理 {@link RunStatus#WAITING_APPROVAL}：这个状态下 run 合理地处于"暂停 + 等人类
 * 决策"的长生命期状态，LiveRunRegistry 不会登记它，但它并没死。</p>
 */
@Service
public class StaleRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleRunReconciler.class);
    private static final String RECONCILE_DETAIL =
            "auto-terminated: server has no live execution for this run (likely crashed or connection dropped)";
    private static final String HARD_STALE_DETAIL =
            "auto-terminated: run stayed in-progress past 60 min without status updates — treating as zombie despite LiveRunRegistry entry";

    // 最长合理 in-progress 时间。我们的超时预算大约:最多 20 次重试 * 300s 读超时 ≈ 100min 单次模型请求,
    // 叠加最多 100 轮 tool loop —— 纸面天花板是几个小时,但现实里任何活到 60 分钟没 status update 的 run
    // 都要么是卡死,要么是 LiveRunRegistry 泄漏。阈值设 60min 是"显然不可能再推进"的安全线。
    private static final Duration HARD_STALE_THRESHOLD = Duration.ofMinutes(60);

    private static final Set<String> IN_PROGRESS_STATUSES = Set.of(
            RunStatus.RECEIVED.name(),
            RunStatus.CONTEXT_BUILT.name(),
            RunStatus.MODEL_RUNNING.name(),
            RunStatus.TOOL_REQUESTED.name(),
            RunStatus.TOOL_EXECUTING.name(),
            RunStatus.TOOL_RESULT_APPENDED.name()
    );

    private final RunRecordRepository runRecordRepository;
    private final LiveRunRegistry liveRunRegistry;

    public StaleRunReconciler(
            RunRecordRepository runRecordRepository,
            LiveRunRegistry liveRunRegistry
    ) {
        this.runRecordRepository = runRecordRepository;
        this.liveRunRegistry = liveRunRegistry;
    }

    /**
     * 如果传入的 entity 状态是 in-progress 但 {@link LiveRunRegistry} 找不到该 runId，
     * 把 DB 状态写成 FAILED 并回写 entity（便于直接返回给前端）。返回值是 {@code true} 当且仅当
     * 发生了修正。
     */
    @Transactional
    public boolean reconcileIfStale(RunRecordEntity entity) {
        if (entity == null) {
            return false;
        }
        String status = entity.getStatus();
        if (!IN_PROGRESS_STATUSES.contains(status)) {
            return false;
        }
        String runId = entity.getId();
        // 两条独立的判死路径:
        //   1) LiveRunRegistry 找不到 -> 标准情况(进程崩溃重启 / finally 正常清理)
        //   2) LiveRunRegistry 说活着但 updated_at 老于硬阈值 -> 兜底(registry 泄漏 / worker 线程
        //      卡死没走到 finally),观察到过 2e6e9f2c 会话里 LLM 反复被 validator 拒后 stream 悄悄
        //      挂掉但 LiveRunRegistry 没清,run 永远显示 MODEL_RUNNING。
        boolean registrySaysDead = !liveRunRegistry.isActive(runId);
        boolean hardStale = isHardStale(entity);
        if (!registrySaysDead && !hardStale) {
            return false;
        }
        String reason = registrySaysDead ? RECONCILE_DETAIL : HARD_STALE_DETAIL;
        log.warn("run.reconcile.stale runId={}, previousStatus={}, updatedAt={}, registryActive={}, hardStale={}, reason={}",
                runId, status, entity.getUpdatedAt(), !registrySaysDead, hardStale, reason);
        entity.setStatus(RunStatus.FAILED.name());
        entity.setDetail(reason);
        runRecordRepository.save(entity);
        return true;
    }

    private boolean isHardStale(RunRecordEntity entity) {
        Instant updatedAt = entity.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        return Duration.between(updatedAt, Instant.now()).compareTo(HARD_STALE_THRESHOLD) > 0;
    }

    public static String reconcileDetail() {
        return RECONCILE_DETAIL;
    }
}
