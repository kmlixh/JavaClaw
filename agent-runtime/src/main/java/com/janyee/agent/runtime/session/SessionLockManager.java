package com.janyee.agent.runtime.session;

import java.time.Duration;

public interface SessionLockManager {
    boolean tryLock(String sessionId, Duration timeout);

    void unlock(String sessionId);
}
