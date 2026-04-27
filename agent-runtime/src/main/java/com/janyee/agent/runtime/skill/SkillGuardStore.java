package com.janyee.agent.runtime.skill;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local index of active {@link SkillGuard}s keyed by runId. The orchestrator
 * registers a guard at run start and unregisters in the run's finally block, mirroring
 * {@link com.janyee.agent.runtime.loop.RunPlanStore}. Tools pull the guard by runId from
 * their {@code ToolInvocation}.
 *
 * Missing entries mean "no enforcement" rather than "deny"; tools must not hard-fail when
 * the store returns empty, because legacy code paths and test harnesses may not populate it.
 */
@Component
public class SkillGuardStore {

    private final ConcurrentMap<String, SkillGuard> guardsByRunId = new ConcurrentHashMap<>();

    public void register(String runId, SkillGuard guard) {
        if (runId == null || runId.isBlank() || guard == null) {
            return;
        }
        guardsByRunId.put(runId, guard);
    }

    public Optional<SkillGuard> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(guardsByRunId.get(runId));
    }

    public void unregister(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        guardsByRunId.remove(runId);
    }

    public int activeRunCount() {
        return guardsByRunId.size();
    }
}
