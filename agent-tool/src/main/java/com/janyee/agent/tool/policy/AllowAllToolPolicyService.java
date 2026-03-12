package com.janyee.agent.tool.policy;

import org.springframework.stereotype.Service;

@Service
public class AllowAllToolPolicyService implements ToolPolicyService {
    @Override
    public boolean isAllowed(String agentId, String toolName) {
        return true;
    }
}
