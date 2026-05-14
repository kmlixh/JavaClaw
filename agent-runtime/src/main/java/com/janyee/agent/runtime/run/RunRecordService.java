package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.domain.ChatContextReference;
import com.janyee.agent.domain.RunStatus;

import java.util.List;

public interface RunRecordService {
    String createAcceptedRun(
            String sessionId,
            String agentId,
            String userId,
            String message,
            List<ChatContextReference> references,
            List<ChatAttachment> attachments,
            String llmConfigId,
            String llmProvider,
            String llmModel
    );

    /**
     * 跟 createAcceptedRun 一样,但允许调用方<b>指定 runId</b>(而不是后端生成 UUID)。
     * 用在 lab runner / 嵌入路径等"自带 runId"的场景 —— 这些路径如果不预创建 run_record 行,
     * 后续 updateStatus / attachEventLog 全部走 findById().ifPresent() 静默 no-op,
     * 复盘日志永远落不下来。
     */
    void createAcceptedRunWithId(
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
    );

    void updateStatus(String runId, RunStatus status, String detail);

    /**
     * 只有当 DB 现在还停在 in-progress(RECEIVED / CONTEXT_BUILT / MODEL_RUNNING /
     * TOOL_REQUESTED / TOOL_EXECUTING / TOOL_RESULT_APPENDED)时才写入目标状态;
     * 已经处于 COMPLETED / FAILED / CANCELLED / WAITING_APPROVAL 等终态/挂起态时<b>不动</b>。
     *
     * <p>SimpleAgentRunner 的 finally 用它做"防御兜底"——如果主流程或 catch 块自己又抛了
     * Throwable 没写到终态,这里再补一刀 FAILED;反过来,如果 catch 已经正确写过 FAILED,
     * 这次调用就 no-op,不会覆盖 catch 提供的真实失败原因。</p>
     *
     * @return true 表示真的改了 DB,false 表示状态已经是终态/未知 runId,跳过
     */
    boolean updateStatusIfInProgress(String runId, RunStatus status, String detail);

    /**
     * Persist the current RunPlan snapshot for this run. Called on every plan mutation
     * (plan.create / plan.update / auto-seed). Historical runs surface this via the run
     * detail API so users see retroactively how the run planned its steps.
     * Pass null/empty to clear.
     */
    void updatePlan(String runId, String planJson);

    /**
     * Persist the full event-log JSON for this run. Used for post-mortem replay
     * (front-end timeline / flow chart). Called once at run end.
     */
    void attachEventLog(String runId, String eventLogJson);

    /**
     * Persist the unfiltered raw event log (TOKEN_DELTA + streaming MODEL_OUTPUT chunks included).
     * Called once at run end. Stored in {@code run_record.raw_log_text}.
     */
    void attachRawLog(String runId, String rawLogText);
}
