package com.janyee.agent.infra.run;

import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 兜底地把"60 分钟以上完全没有 DB 写入"的 in-progress run 改成 FAILED。
 *
 * <p>历史上这个类还做过另一条事:用 LiveRunRegistry 当"是否在跑"的真相,只要 registry 没这个
 * runId 就 FAIL 掉。那条路径被删了,原因:registry 是进程内 ConcurrentHashMap,任何一次漏 register
 * 或者状态不一致都会把一条**真正在跑**的 run 误杀。代价远大于收益,而且它要解决的"JVM 崩溃后留
 * 下僵尸条目"已经由 {@link RunStartupRecoveryService}(启动时重排队/标记)负责,不该在读 DB
 * 顺手做。读路径不应该有副作用。</p>
 *
 * <p>剩下唯一保留的路径:run 的 updated_at 已经 60 分钟以上没动。每次 plan.update / status 切换 /
 * 任何 runRecordRepository.save(entity) 都会触发 @PreUpdate 把 updated_at 推到现在。所以 updated_at
 * 静默 60 分钟意味着 run 真的卡死了(线程死循环 / 第三方调用永远挂着 / 调度器吞错),这种情况
 * 安全标记 FAILED 不会误伤——同期没有 catch / 没有 finally / 没有任何工具调用更新 DB。</p>
 *
 * <p>不处理 {@link RunStatus#WAITING_APPROVAL}:这个状态合理地等人类决策,长生命期挂着是预期。</p>
 */
@Service
public class StaleRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleRunReconciler.class);
    private static final String HARD_STALE_DETAIL =
            "auto-terminated: run stayed in-progress past 60 min without any DB activity (likely deadlocked or thread died silently)";

    // 60 min 的阈值依据:LLM 单次请求最长重试 20 次 * 300s 读超时 ≈ 100min 是纸面天花板,但实际
    // 任何活到 60 分钟连一次 plan.update / status 切换都没有的 run 都是真卡死了。低过这个数会误伤
    // 长 prompt 调用,高过这个数前端要等更久才能看到 FAILED。
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

    public StaleRunReconciler(RunRecordRepository runRecordRepository) {
        this.runRecordRepository = runRecordRepository;
    }

    /**
     * 唯一一条修正路径:in-progress 状态 + updated_at 已经超过 {@link #HARD_STALE_THRESHOLD}。
     * 命中就改写 entity + 持久化。其他所有"我猜它死了"的启发式判断都不在这里——只有"60min 完全
     * 没动"这种可以确凿断言的死亡才动手。
     *
     * @return true 当且仅当本次真把状态改成了 FAILED
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
        if (!isHardStale(entity)) {
            return false;
        }
        String runId = entity.getId();
        log.warn("run.reconcile.hard_stale runId={}, previousStatus={}, updatedAt={}, reason={}",
                runId, status, entity.getUpdatedAt(), HARD_STALE_DETAIL);
        entity.setStatus(RunStatus.FAILED.name());
        entity.setDetail(HARD_STALE_DETAIL);
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

    public static String hardStaleDetail() {
        return HARD_STALE_DETAIL;
    }
}
