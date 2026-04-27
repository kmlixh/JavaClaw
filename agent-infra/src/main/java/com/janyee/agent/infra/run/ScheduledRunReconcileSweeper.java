package com.janyee.agent.infra.run;

import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 周期扫描所有 in-progress run 喂给 {@link StaleRunReconciler}。
 *
 * <p>原本的 reconciler 只在用户打开会话(getRun/listRuns/findActiveRun)时触发。如果用户从不
 * 回来,僵尸 run 会永远挂在 MODEL_RUNNING。这个定时任务独立于前端行为,保证"最多 5 分钟内
 * 所有真正死亡的 run 都会被写回 FAILED"。</p>
 *
 * <p>注意 StaleRunReconciler 只 flip 符合两个独立判死路径之一的 run(LiveRunRegistry 说死 /
 * updated_at 超 60min)。合理在跑的 run 不会被误伤。</p>
 */
@Component
public class ScheduledRunReconcileSweeper {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRunReconcileSweeper.class);
    private static final List<String> IN_PROGRESS_STATUSES = List.of(
            RunStatus.RECEIVED.name(),
            RunStatus.CONTEXT_BUILT.name(),
            RunStatus.MODEL_RUNNING.name(),
            RunStatus.TOOL_REQUESTED.name(),
            RunStatus.TOOL_EXECUTING.name(),
            RunStatus.TOOL_RESULT_APPENDED.name()
    );

    private final RunRecordRepository runRecordRepository;
    private final StaleRunReconciler staleRunReconciler;

    public ScheduledRunReconcileSweeper(
            RunRecordRepository runRecordRepository,
            StaleRunReconciler staleRunReconciler
    ) {
        this.runRecordRepository = runRecordRepository;
        this.staleRunReconciler = staleRunReconciler;
    }

    /**
     * 每 5 分钟跑一次。initialDelay 给启动恢复留足时间 —— RunStartupRecoveryService 已经在
     * ApplicationRunner 阶段把启动前遗留的 in-progress run 改成 FAILED,重复扫一次没坏处。
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void sweep() {
        List<RunRecordEntity> candidates = runRecordRepository.findByStatusIn(IN_PROGRESS_STATUSES);
        if (candidates.isEmpty()) {
            return;
        }
        int reconciled = 0;
        for (RunRecordEntity entity : candidates) {
            try {
                if (staleRunReconciler.reconcileIfStale(entity)) {
                    reconciled++;
                }
            } catch (Exception error) {
                log.warn("run.reconcile.sweep_one_failed runId={}, cause={}", entity.getId(), error.getMessage());
            }
        }
        if (reconciled > 0) {
            log.info("run.reconcile.sweep candidates={}, reconciled={}", candidates.size(), reconciled);
        }
    }
}
