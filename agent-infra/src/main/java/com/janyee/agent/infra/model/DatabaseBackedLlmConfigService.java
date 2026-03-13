package com.janyee.agent.infra.model;

import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import com.janyee.agent.infra.persistence.repository.LlmProviderConfigRepository;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DatabaseBackedLlmConfigService implements LlmConfigService {

    private final LlmProviderConfigRepository repository;
    private final AgentPlatformProperties properties;

    public DatabaseBackedLlmConfigService(LlmProviderConfigRepository repository, AgentPlatformProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public List<LlmConfigDescriptor> listAvailable() {
        List<LlmConfigDescriptor> configs = repository.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .map(this::toDescriptor)
                .toList();
        if (!configs.isEmpty()) {
            return configs;
        }
        return fallbackConfig().stream().toList();
    }

    @Override
    public Optional<LlmConfigDescriptor> findById(String configId) {
        if (configId == null || configId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByIdAndEnabledTrue(configId)
                .map(this::toDescriptor);
    }

    @Override
    public Optional<LlmConfigDescriptor> findDefault() {
        Optional<LlmConfigDescriptor> databaseDefault = repository.findFirstByEnabledTrueAndDefaultConfigTrueOrderByDisplayNameAsc()
                .map(this::toDescriptor);
        if (databaseDefault.isPresent()) {
            return databaseDefault;
        }
        return fallbackConfig();
    }

    @Override
    public Optional<LlmConfigDescriptor> resolveRequested(String configId) {
        if (configId != null && !configId.isBlank()) {
            Optional<LlmConfigDescriptor> selected = findById(configId);
            if (selected.isPresent()) {
                return selected;
            }
            throw new IllegalArgumentException("llm config not found or disabled: " + configId);
        }
        return findDefault();
    }

    private Optional<LlmConfigDescriptor> fallbackConfig() {
        AgentPlatformProperties.LlmProperties llm = properties.llm();
        if (llm == null || !llm.enabled() || isBlank(llm.baseUrl())) {
            return Optional.empty();
        }
        return Optional.of(new LlmConfigDescriptor(
                "application-default",
                defaultIfBlank(llm.provider(), "openai-compatible"),
                "Application Default",
                llm.model(),
                llm.baseUrl(),
                llm.apiKey(),
                llm.chatPath(),
                llm.stream(),
                true,
                true
        ));
    }

    private LlmConfigDescriptor toDescriptor(LlmProviderConfigEntity entity) {
        return new LlmConfigDescriptor(
                entity.getId(),
                entity.getProvider(),
                entity.getDisplayName(),
                entity.getModel(),
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getChatPath(),
                entity.isStreamEnabled(),
                entity.isEnabled(),
                entity.isDefaultConfig()
        );
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
