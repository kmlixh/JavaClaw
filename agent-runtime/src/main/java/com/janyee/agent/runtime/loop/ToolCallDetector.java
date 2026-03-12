package com.janyee.agent.runtime.loop;

import java.util.Optional;

public interface ToolCallDetector {
    Optional<ToolCallRequest> detect(ModelTurnResult result);
}
