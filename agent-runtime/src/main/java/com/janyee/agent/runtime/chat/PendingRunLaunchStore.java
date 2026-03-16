package com.janyee.agent.runtime.chat;

import java.util.Optional;

public interface PendingRunLaunchStore {
    void save(PendingRunLaunch launch);
    Optional<PendingRunLaunch> find(String runId);
    Optional<PendingRunLaunch> consume(String runId);
}
