package com.janyee.agent.infra.run;

import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class RunStartupRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunStartupRecoveryService.class);
    private static final String RECOVERY_DETAIL = "service restarted before run completed";
    private static final List<String> INTERRUPTED_STATUSES = List.of(
            RunStatus.RECEIVED.name(),
            RunStatus.CONTEXT_BUILT.name(),
            RunStatus.MODEL_RUNNING.name(),
            RunStatus.TOOL_REQUESTED.name(),
            RunStatus.TOOL_EXECUTING.name(),
            RunStatus.TOOL_RESULT_APPENDED.name()
    );

    private final RunRecordRepository runRecordRepository;

    public RunStartupRecoveryService(RunRecordRepository runRecordRepository) {
        this.runRecordRepository = runRecordRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<RunRecordEntity> interruptedRuns = runRecordRepository.findByStatusIn(INTERRUPTED_STATUSES);
        if (interruptedRuns.isEmpty()) {
            log.info("run.startup_recovery no interrupted runs");
            return;
        }
        log.warn(
                "run.startup_recovery marking interrupted runs failed count={}, runIds={}",
                interruptedRuns.size(),
                interruptedRuns.stream().map(RunRecordEntity::getId).toList()
        );
        int updated = runRecordRepository.failRunsByStatusIn(INTERRUPTED_STATUSES, RECOVERY_DETAIL);
        log.warn("run.startup_recovery completed updated={}, detail={}", updated, RECOVERY_DETAIL);
    }
}
