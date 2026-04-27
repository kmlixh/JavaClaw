package com.janyee.agent.runtime.skill;

import java.util.List;

/**
 * Loads structured constraints for the agent's enabled skills so the runtime can
 * enforce whitelisted tables and mandated plan step IDs independently of the LLM.
 */
public interface SkillConstraintService {
    List<SkillConstraint> listActive(String agentId);
}
