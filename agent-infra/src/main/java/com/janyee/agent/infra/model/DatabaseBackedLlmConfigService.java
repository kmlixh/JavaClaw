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
        // 1) 优先走 DB 里 is_default=true 的 LLM
        Optional<LlmConfigDescriptor> databaseDefault = repository.findFirstByEnabledTrueAndDefaultConfigTrueOrderByDisplayNameAsc()
                .map(this::toDescriptor);
        if (databaseDefault.isPresent()) {
            return databaseDefault;
        }
        // 2) 没显式标 default 时,挑第一个 enabled 的 DB LLM 兜底 ——
        //    比直接退到 application-default(yml 里那条 token 经常过期 / 不该用)更靠谱。
        Optional<LlmConfigDescriptor> firstEnabled = repository.findByEnabledTrueOrderByDisplayNameAsc()
                .stream().findFirst().map(this::toDescriptor);
        if (firstEnabled.isPresent()) {
            return firstEnabled;
        }
        // 3) DB 都没 LLM 才回到 application-prod.yml 的 application-default 兜底
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
                llm.model() == null || llm.model().isBlank()
                        ? "{\"models\":[]}"
                        : "{\"models\":[{\"displayName\":\"" + llm.model() + "\",\"apiModel\":\"" + llm.model() + "\"}]}",
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
                entity.getModelMappingJson(),
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
