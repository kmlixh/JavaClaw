package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.RunStatus;

public interface RunRecordService {
    String createAcceptedRun(String sessionId, String agentId, String userId, String message);

    void updateStatus(String runId, RunStatus status, String detail);
}
