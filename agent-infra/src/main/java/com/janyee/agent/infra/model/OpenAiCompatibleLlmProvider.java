package com.janyee.agent.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Primary
@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);

    private final AgentPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public OpenAiCompatibleLlmProvider(AgentPlatformProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Flux<LlmStreamEvent> chatStream(LlmChatRequest request) {
        AgentPlatformProperties.LlmProperties llm = properties.llm();
        if (llm == null || !llm.enabled() || isBlank(llm.baseUrl())) {
            return StubLlmProvider.response(request);
        }

        return requestCompletion(request, llm)
                .flatMapMany(this::mapResponseToEvents)
                .onErrorResume(error -> {
                    log.warn("openai-compatible provider failed, fallback to stub: {}", error.getMessage());
                    return StubLlmProvider.response(request);
                });
    }

    private Mono<String> requestCompletion(LlmChatRequest request, AgentPlatformProperties.LlmProperties llm) {
        Map<String, Object> payload = Map.of(
                "model", defaultIfBlank(request.model(), llm.model()),
                "stream", llm.stream(),
                "messages", List.of(Map.of("role", "user", "content", request.prompt()))
        );

        return webClient.post()
                .uri(joinUrl(llm.baseUrl(), defaultIfBlank(llm.chatPath(), "/v1/chat/completions")))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, llm))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class);
    }

    private Flux<LlmStreamEvent> mapResponseToEvents(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return Flux.just(new LlmStreamEvent("error", "missing choices in llm response"));
            }

            JsonNode firstChoice = choices.get(0);
            String content = firstChoice.path("message").path("content").asText("");
            if (content.isBlank()) {
                content = firstChoice.path("text").asText("");
            }

            return Flux.just(
                    new LlmStreamEvent("token", content),
                    new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("stop"))
            );
        } catch (Exception error) {
            return Flux.just(new LlmStreamEvent("error", "invalid llm response: " + error.getMessage()));
        }
    }

    private void applyAuth(HttpHeaders headers, AgentPlatformProperties.LlmProperties llm) {
        if (!isBlank(llm.apiKey())) {
            headers.setBearerAuth(llm.apiKey());
        }
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
