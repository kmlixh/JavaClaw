package com.janyee.agent.infra.session;

import com.janyee.agent.runtime.session.SessionLockManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "agent.session-lock", name = "type", havingValue = "in-memory", matchIfMissing = true)
public class InMemorySessionLockManager implements SessionLockManager {

    private final Set<String> locks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryLock(String sessionId, Duration timeout) {
        return locks.add(sessionId);
    }

    @Override
    public void unlock(String sessionId) {
        locks.remove(sessionId);
    }
}
