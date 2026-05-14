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
        String runId = UUID.randomUUID().toString();
        doCreate(runId, sessionId, agentId, userId, message, references, attachments,
                llmConfigId, llmProvider, llmModel);
        return runId;
    }

    @Override
    @Transactional
    public void createAcceptedRunWithId(
            String runId,
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
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        // 幂等:如果已经存在(例如 lab 重启)直接跳过,不抢覆盖
        if (runRecordRepository.findById(runId).isPresent()) {
            return;
        }
        doCreate(runId, sessionId, agentId, userId, message, references, attachments,
                llmConfigId, llmProvider, llmModel);
    }

    private void doCreate(
            String runId, String sessionId, String agentId, String userId,
            String message, List<ChatContextReference> references, List<ChatAttachment> attachments,
            String llmConfigId, String llmProvider, String llmModel
    ) {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(runId);
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        // user_id 以 principal 为准,避免前端传的 userId 跟 OAuth token 真实身份不一致
        // (跟 InMemorySessionService.createSession 保持同一口径,确保 listSessions 按
        // principal.userId 过滤时能对得上)。匿名时退回到 controller 传的 userId。
        com.janyee.agent.infra.auth.AuthPrincipal principal =
                com.janyee.agent.infra.auth.SecurityContextHolder.current();
        String resolvedUserId = principal != null && !principal.anonymous()
                && principal.userId() != null && !principal.userId().isBlank()
                ? principal.userId()
                : (userId == null || userId.isBlank() ? "anonymous" : userId);
        entity.setUserId(resolvedUserId);
        entity.setLlmConfigId(llmConfigId);
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setStatus(RunStatus.RECEIVED.name());
        entity.setDetail(message);
        entity.setRequestMessage(message);
        entity.setRequestReferencesJson(writeJson(references));
        entity.setRequestAttachmentsJson(writeJson(attachments));
        // P3:同 session,run 也带上 tenant+app 便于事后筛选。
        if (principal != null) {
            entity.setTenantId(principal.tenantId());
            entity.setAppId(principal.appId());
        }
        runRecordRepository.save(entity);
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

    private static final java.util.Set<String> IN_PROGRESS_STATUSES = java.util.Set.of(
            RunStatus.RECEIVED.name(),
            RunStatus.CONTEXT_BUILT.name(),
            RunStatus.MODEL_RUNNING.name(),
            RunStatus.TOOL_REQUESTED.name(),
            RunStatus.TOOL_EXECUTING.name(),
            RunStatus.TOOL_RESULT_APPENDED.name()
    );

    @Override
    @Transactional
    public boolean updateStatusIfInProgress(String runId, RunStatus status, String detail) {
        return runRecordRepository.findById(runId)
                .map(entity -> {
                    if (!IN_PROGRESS_STATUSES.contains(entity.getStatus())) {
                        return false;
                    }
                    entity.setStatus(status.name());
                    entity.setDetail(detail);
                    runRecordRepository.save(entity);
                    return true;
                })
                .orElse(false);
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

    @Override
    @Transactional
    public void attachEventLog(String runId, String eventLogJson) {
        if (runId == null || runId.isBlank()) return;
        runRecordRepository.findById(runId).ifPresent(entity -> {
            entity.setEventLogJson(eventLogJson == null || eventLogJson.isBlank() ? null : eventLogJson);
            runRecordRepository.save(entity);
        });
    }

    @Override
    @Transactional
    public void attachRawLog(String runId, String rawLogText) {
        if (runId == null || runId.isBlank()) return;
        runRecordRepository.findById(runId).ifPresent(entity -> {
            entity.setRawLogText(rawLogText == null || rawLogText.isBlank() ? null : rawLogText);
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
