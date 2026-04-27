-- Persist the per-run plan snapshot (current state of RunPlan) on run_record, so historical
-- runs can surface their plan in the run detail view. The in-memory RunPlanStore is still
-- the source of truth while the run is live; we sync to this column after every mutation
-- (plan.create / plan.update / auto-seed). Nullable because: not every run has a plan
-- (plain chat, no skill-driven plan required).
ALTER TABLE run_record
    ADD COLUMN IF NOT EXISTS plan_json TEXT;
