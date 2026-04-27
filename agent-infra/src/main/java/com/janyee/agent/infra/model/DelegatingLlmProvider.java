package com.janyee.agent.infra.model;

import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Primary
@Component
public class DelegatingLlmProvider implements LlmProvider {

    private final LlmConfigService llmConfigService;
    private final AgentPlatformProperties properties;
    private final OpenAiCompatibleLlmProvider openAiCompatibleLlmProvider;
    private final DifyLlmProvider difyLlmProvider;
    private final JiutianLlmProvider jiutianLlmProvider;

    public DelegatingLlmProvider(
            LlmConfigService llmConfigService,
            AgentPlatformProperties properties,
            OpenAiCompatibleLlmProvider openAiCompatibleLlmProvider,
            DifyLlmProvider difyLlmProvider,
            JiutianLlmProvider jiutianLlmProvider
    ) {
        this.llmConfigService = llmConfigService;
        this.properties = properties;
        this.openAiCompatibleLlmProvider = openAiCompatibleLlmProvider;
        this.difyLlmProvider = difyLlmProvider;
        this.jiutianLlmProvider = jiutianLlmProvider;
    }

    @Override
    public Flux<LlmStreamEvent> chatStream(LlmChatRequest request) {
        LlmConfigDescriptor llm = resolveConfig(request.configId());
        if (llm == null || !llm.enabled()) {
            return StubLlmProvider.response(request);
        }
        if ("dify".equalsIgnoreCase(llm.provider())) {
            return difyLlmProvider.chatStream(request, llm);
        }
        if ("jiutian".equalsIgnoreCase(llm.provider())) {
            return jiutianLlmProvider.chatStream(request, llm);
        }
        return openAiCompatibleLlmProvider.chatStream(request, llm);
    }

    private LlmConfigDescriptor resolveConfig(String configId) {
        return llmConfigService.resolveRequested(configId)
                .orElseGet(() -> {
                    AgentPlatformProperties.LlmProperties llm = properties.llm();
                    if (llm == null) {
                        return null;
                    }
                    return new LlmConfigDescriptor(
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
                            llm.enabled(),
                            true
                    );
                });
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
