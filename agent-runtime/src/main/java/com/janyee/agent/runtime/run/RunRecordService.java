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

    void updateStatus(String runId, RunStatus status, String detail);

    /**
     * Persist the current RunPlan snapshot for this run. Called on every plan mutation
     * (plan.create / plan.update / auto-seed). Historical runs surface this via the run
     * detail API so users see retroactively how the run planned its steps.
     * Pass null/empty to clear.
     */
    void updatePlan(String runId, String planJson);
}
