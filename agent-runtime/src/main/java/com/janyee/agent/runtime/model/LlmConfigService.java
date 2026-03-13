package com.janyee.agent.runtime.model;

import java.util.List;
import java.util.Optional;

public interface LlmConfigService {
    List<LlmConfigDescriptor> listAvailable();

    Optional<LlmConfigDescriptor> findById(String configId);

    Optional<LlmConfigDescriptor> findDefault();

    Optional<LlmConfigDescriptor> resolveRequested(String configId);
}
