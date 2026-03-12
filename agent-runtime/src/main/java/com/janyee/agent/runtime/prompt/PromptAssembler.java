package com.janyee.agent.runtime.prompt;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;

public interface PromptAssembler {
    PromptContext assemble(RunRequest request);
}
