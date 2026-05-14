package com.janyee.agent.infra.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.RunStatus;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程启动时扫一遍 DB,把上次崩溃/重启没跑完的 in-progress run 处理掉。
 *
 * <p>策略(按用户要求):优先**重启执行**,实在跑不起来(message 缺失、agentId 缺失、构造
 * RunRequest 抛异常)的才标 FAILED。这样上次中途被 kill -9 的 run 重启后会自动接着跑,
 * 不会让用户的"分析报告"任务每次重启都白丢一次。</p>
 *
 * <p>重启执行 = 用 DB 里持久化的 message / sessionId / agentId / llmConfigId 重新构造
 * RunRequest,resume=false 让它从头来一遍 —— 跟"用户重新发送一遍"等价。会再 appendUserMessage
 * 一次,产生重复 transcript;不过这是可观察的副作用,优于"任务消失"。</p>
 */
@Component
public class RunStartupRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunStartupRecoveryService.class);
    private static final String UNRECOVERABLE_DETAIL =
            "service restarted and run could not be re-executed (missing required fields in run_record)";
    private static final String RESTART_DETAIL =
            "service restarted; previous attempt aborted, a fresh execution has been kicked off";
    private static final List<String> INTERRUPTED_STATUSES = List.of(
            RunStatus.RECEIVED.name(),
            RunStatus.CONTEXT_BUILT.name(),
            RunStatus.MODEL_RUNNING.name(),
            RunStatus.TOOL_REQUESTED.name(),
            RunStatus.TOOL_EXECUTING.name(),
            RunStatus.TOOL_RESULT_APPENDED.name()
    );

    private final RunRecordRepository runRecordRepository;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;

    public RunStartupRecoveryService(
            RunRecordRepository runRecordRepository,
            AgentRunner agentRunner,
            ObjectMapper objectMapper
    ) {
        this.runRecordRepository = runRecordRepository;
        this.agentRunner = agentRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<RunRecordEntity> interruptedRuns = runRecordRepository.findByStatusIn(INTERRUPTED_STATUSES);
        if (interruptedRuns.isEmpty()) {
            log.info("run.startup_recovery no interrupted runs");
            return;
        }
        log.warn("run.startup_recovery found interrupted runs count={}, runIds={}",
                interruptedRuns.size(),
                interruptedRuns.stream().map(RunRecordEntity::getId).toList());

        List<String> requeued = new ArrayList<>();
        List<String> unrecoverable = new ArrayList<>();
        for (RunRecordEntity entity : interruptedRuns) {
            try {
                RunRequest request = buildRunRequest(entity);
                if (request == null) {
                    unrecoverable.add(entity.getId());
                    continue;
                }
                // 先把 detail 改成"已重排队",避免重启后短暂窗口内前端 GET 看到旧 detail 误判。
                entity.setDetail(RESTART_DETAIL);
                runRecordRepository.save(entity);
                // fire-and-forget:跟 ChatController.send 同样的解耦——run 在 boundedElastic 上跑,
                // 不挂在任何 HTTP 请求 / WS 连接上。
                final String runId = entity.getId();
                agentRunner.run(request).subscribe(
                        event -> { /* events go to RunEventStreamService */ },
                        error -> log.error("run.startup_recovery.requeue_async_error runId={}", runId, error),
                        () -> log.info("run.startup_recovery.requeue_async_complete runId={}", runId)
                );
                requeued.add(entity.getId());
            } catch (Exception error) {
                log.warn("run.startup_recovery.requeue_failed runId={}, cause={}",
                        entity.getId(), error.getMessage());
                unrecoverable.add(entity.getId());
            }
        }
        if (!unrecoverable.isEmpty()) {
            // 实在无法重启的:写 FAILED 终态。SQL 单批 UPDATE 比逐条 save 高效。
            for (String runId : unrecoverable) {
                runRecordRepository.findById(runId).ifPresent(e -> {
                    e.setStatus(RunStatus.FAILED.name());
                    e.setDetail(UNRECOVERABLE_DETAIL);
                    runRecordRepository.save(e);
                });
            }
        }
        log.warn("run.startup_recovery completed requeued={}, unrecoverable={}",
                requeued.size(), unrecoverable.size());
    }

    /**
     * 从 DB 持久化的 run_record 反序列化出 RunRequest。缺关键字段(message / agentId / sessionId)
     * 直接返 null,让调用方走"不可恢复"分支。
     */
    private RunRequest buildRunRequest(RunRecordEntity entity) {
        String runId = entity.getId();
        String sessionId = entity.getSessionId();
        String agentId = entity.getAgentId();
        String userId = entity.getUserId();
        String message = entity.getRequestMessage();
        if (sessionId == null || sessionId.isBlank()
                || agentId == null || agentId.isBlank()
                || message == null || message.isBlank()) {
            return null;
        }
        List<ChatContextReference> references = parseList(
                entity.getRequestReferencesJson(),
                new TypeReference<List<ChatContextReference>>() {}
        );
        List<ChatAttachment> attachments = parseList(
                entity.getRequestAttachmentsJson(),
                new TypeReference<List<ChatAttachment>>() {}
        );
        // 重启重排:resume=false —— 我们没办法可靠恢复之前的中间状态(plan / tool_outcomes /
        // assistantText 都在 boundedElastic 线程的 ToolLoopContext 里,JVM 死了就丢了),
        // 干脆当成"用户重新发了一遍同样消息",从头跑一遍。
        return new RunRequest(
                runId,
                sessionId,
                agentId,
                userId == null ? "anonymous" : userId,
                message,
                false,
                entity.getLlmConfigId(),
                entity.getLlmModel(),
                references,
                attachments,
                // 重启场景下 ThreadLocal 的 SecurityContext 没有了,这里只能用 entity 上记录的
                // tenant/app 来重建。userId 也用 entity 的;principal 字段缺失下游会走匿名兜底。
                userId,
                entity.getTenantId(),
                entity.getAppId()
        );
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> parsed = objectMapper.readValue(json, typeRef);
            return parsed != null ? parsed : List.of();
        } catch (Exception error) {
            log.warn("run.startup_recovery.parse_list_failed cause={}", error.getMessage());
            return List.of();
        }
    }
}
