-- Plan B bolt-on: skill_definition.trigger_keywords for agent-mismatch early warning.
-- Matching logic: if user message contains any listed keyword (case-insensitive) but the
-- session's agent has no skill with that keyword bound, the run fails fast with a clear
-- message ("this agent doesn't have the required skill; switch to xxx-agent") instead of
-- letting the LLM spin for 30 iterations trying to find context that isn't there.
--
-- Stored as a simple JSON array of strings: ["覆盖分析", "扇区统计"]

ALTER TABLE skill_definition
    ADD COLUMN IF NOT EXISTS trigger_keywords TEXT;

UPDATE skill_definition
   SET trigger_keywords = '["覆盖分析","覆盖率","弱覆盖","扇区统计","盘龙区覆盖"]'
 WHERE skill_name = 'skill.coverage.analysis' AND trigger_keywords IS NULL;
