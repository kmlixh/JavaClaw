package com.janyee.agent.infra.run;

import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.run.RunRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JpaRunRecordService implements RunRecordService {

    private final RunRecordRepository runRecordRepository;

    public JpaRunRecordService(RunRecordRepository runRecordRepository) {
        this.runRecordRepository = runRecordRepository;
    }

    @Override
    @Transactional
    public String createAcceptedRun(
            String sessionId,
            String agentId,
            String userId,
            String message,
            String llmConfigId,
            String llmProvider,
            String llmModel
    ) {
        RunRecordEntity entity = new RunRecordEntity();
        String runId = UUID.randomUUID().toString();
        entity.setId(runId);
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setLlmConfigId(llmConfigId);
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setStatus(RunStatus.RECEIVED.name());
        entity.setDetail(message);
        runRecordRepository.save(entity);
        return runId;
    }

    @Override
    @Transactional
    public void updateStatus(String runId, RunStatus status, String detail) {
        runRecordRepository.findById(runId).ifPresent(entity -> {
            entity.setStatus(status.name());
            entity.setDetail(detail);
            runRecordRepository.save(entity);
        });
    }
}
