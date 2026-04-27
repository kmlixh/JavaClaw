package com.janyee.agent.runtime.run;

import com.janyee.agent.runtime.loop.ToolLoopContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps {@code runId → ToolLoopContext} so an HTTP controller can flip the cancel flag
 * on a run executing inside the reactive elastic pool. The orchestrator polls
 * {@link ToolLoopContext#isCancelRequested()} at the top of each iteration and bails
 * out cleanly on the next checkpoint.
 *
 * <p>Intentionally separate from {@link LiveRunRegistry}: that one tracks "this runId
 * is alive" with a lightweight timestamp, and its API shape is used by
 * {@code StaleRunReconciler} / tests. Mixing the cancel mechanism in would force
 * every call site there to import the ToolLoopContext type from a lower layer.</p>
 */
@Component
public class RunCancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunCancellationRegistry.class);

    private final Map<String, ToolLoopContext> activeContexts = new ConcurrentHashMap<>();

    public void register(String runId, ToolLoopContext context) {
        if (runId == null || runId.isBlank() || context == null) {
            return;
        }
        activeContexts.put(runId, context);
    }

    public void unregister(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        activeContexts.remove(runId);
    }

    /**
     * Signal cancellation for {@code runId}. Returns true if a context was registered
     * (and got the signal); false if the run is not active on this process — caller
     * should then decide whether to fall back to DB status update or return 404.
     */
    public boolean requestCancel(String runId, String reason) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        ToolLoopContext context = activeContexts.get(runId);
        if (context == null) {
            return false;
        }
        context.requestCancel(reason);
        log.info("run.cancel.requested runId={}, reason={}", runId, reason);
        return true;
    }

    public boolean isActive(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        return activeContexts.containsKey(runId);
    }

    public int size() {
        return activeContexts.size();
    }

    public Set<String> activeRunIds() {
        return Set.copyOf(activeContexts.keySet());
    }
}
