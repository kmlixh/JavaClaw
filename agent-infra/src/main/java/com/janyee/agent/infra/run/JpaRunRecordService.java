package com.janyee.agent.infra.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.run.RunRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class JpaRunRecordService implements RunRecordService {

    private final RunRecordRepository runRecordRepository;
    private final ObjectMapper objectMapper;

    public JpaRunRecordService(RunRecordRepository runRecordRepository, ObjectMapper objectMapper) {
        this.runRecordRepository = runRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String createAcceptedRun(
            String sessionId,
            String agentId,
            String userId,
            String message,
            List<ChatContextReference> references,
            List<ChatAttachment> attachments,
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
        entity.setRequestMessage(message);
        entity.setRequestReferencesJson(writeJson(references));
        entity.setRequestAttachmentsJson(writeJson(attachments));
        // P3:同 session,run 也带上 tenant+app 便于事后筛选。
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                com.janyee.agent.infra.auth.SecurityContextHolder.current();
        entity.setTenantId(principal.tenantId());
        entity.setAppId(principal.appId());
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

    @Override
    @Transactional
    public void updatePlan(String runId, String planJson) {
        if (runId == null || runId.isBlank()) return;
        runRecordRepository.findById(runId).ifPresent(entity -> {
            // 空串当成"无 plan"处理 -> null;前端读到 null 就不渲染 plan 面板。
            entity.setPlanJson(planJson == null || planJson.isBlank() ? null : planJson);
            runRecordRepository.save(entity);
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize run request payload", error);
        }
    }
}
