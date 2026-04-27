package com.janyee.agent.infra.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.run.RunRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes the current RunPlan snapshot and writes it to run_record.plan_json.
 * Call sites: plan.create, plan.update, and the SkillGuardResolver auto-seed path.
 * <p>
 * Persistence failures are logged and swallowed — losing the DB snapshot must not kill
 * the run, the live RunPlanStore still holds the authoritative state for the in-flight
 * tool loop.
 */
@Component
public class RunPlanPersister {

    private static final Logger log = LoggerFactory.getLogger(RunPlanPersister.class);

    private final RunRecordService runRecordService;
    private final ObjectMapper objectMapper;

    public RunPlanPersister(RunRecordService runRecordService, ObjectMapper objectMapper) {
        this.runRecordService = runRecordService;
        this.objectMapper = objectMapper;
    }

    public void sync(String runId, RunPlan plan) {
        if (runId == null || runId.isBlank() || plan == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(plan.toSnapshot());
            runRecordService.updatePlan(runId, json);
        } catch (Exception error) {
            log.warn("run_plan.persist_failed runId={}, cause={}", runId, error.getMessage());
        }
    }
}
