package com.janyee.agent.runtime.loop;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local index of active RunPlans, keyed by runId. Plan tools resolve the current
 * run's plan through this store because AgentTool.execute does not otherwise receive a
 * reference to the surrounding ToolLoopContext.
 *
 * The orchestrator is expected to register the plan at run start and unregister at run
 * termination; entries must not leak across runs.
 */
@Component
public class RunPlanStore {

    private final ConcurrentMap<String, RunPlan> plansByRunId = new ConcurrentHashMap<>();

    public void register(String runId, RunPlan plan) {
        if (runId == null || runId.isBlank() || plan == null) {
            return;
        }
        plansByRunId.put(runId, plan);
    }

    public Optional<RunPlan> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(plansByRunId.get(runId));
    }

    public void unregister(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        plansByRunId.remove(runId);
    }

    public int activeRunCount() {
        return plansByRunId.size();
    }
}
