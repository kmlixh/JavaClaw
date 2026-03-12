package com.janyee.agent.tool.policy;

public interface ToolPolicyService {
    boolean isAllowed(String agentId, String toolName);
}
