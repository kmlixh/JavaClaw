package com.janyee.agent.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class DifyLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DifyLlmProvider.class);

    private final AgentPlatformProperties properties;
    private final LlmConfigService llmConfigService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DifyLlmProvider(
            AgentPlatformProperties properties,
            LlmConfigService llmConfigService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.llmConfigService = llmConfigService;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .responseTimeout(Duration.ofSeconds(120));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Flux<LlmStreamEvent> chatStream(LlmChatRequest request) {
        LlmConfigDescriptor llm = resolveConfig(request.configId());
        if (llm == null || !llm.enabled() || isBlank(llm.baseUrl())) {
            return StubLlmProvider.response(request);
        }
        return chatStream(request, llm);
    }

    public Flux<LlmStreamEvent> chatStream(LlmChatRequest request, LlmConfigDescriptor llm) {
        return requestCompletion(request, llm)
                .flatMapMany(this::mapResponseToEvents)
                .onErrorResume(error -> {
                    String errorDetail = describeError(error);
                    log.warn("dify provider failed for config {}: {}", llm.configId(), errorDetail, error);
                    return Flux.error(new IllegalStateException(
                            "Configured LLM request failed for " + llm.displayName() + ": " + errorDetail,
                            error
                    ));
                });
    }

    private Mono<String> requestCompletion(LlmChatRequest request, LlmConfigDescriptor llm) {
        return webClient.post()
                .uri(resolveEndpoint(llm))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, llm))
                .bodyValue(basePayload(request, llm))
                .retrieve()
                .bodyToMono(String.class);
    }

    private Flux<LlmStreamEvent> mapResponseToEvents(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) {
                return Flux.just(
                        new LlmStreamEvent("token", answer),
                        new LlmStreamEvent("finish", "stop")
                );
            }
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return Flux.just(
                        new LlmStreamEvent("token", message),
                        new LlmStreamEvent("finish", "stop")
                );
            }
            return Flux.just(new LlmStreamEvent("error", "missing answer in dify response"));
        } catch (Exception error) {
            return Flux.just(new LlmStreamEvent("error", "invalid dify response: " + error.getMessage()));
        }
    }

    private Map<String, Object> basePayload(LlmChatRequest request, LlmConfigDescriptor llm) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("inputs", Map.of());
        payload.put("query", request.prompt());
        payload.put("response_mode", llm.stream() ? "streaming" : "blocking");
        payload.put("user", "java-claw");
        payload.put("auto_generate_name", false);
        return payload;
    }

    private void applyAuth(HttpHeaders headers, LlmConfigDescriptor llm) {
        if (!isBlank(llm.apiKey())) {
            headers.setBearerAuth(llm.apiKey());
        }
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

    private String resolveEndpoint(LlmConfigDescriptor llm) {
        String url = defaultIfBlank(llm.baseUrl(), "");
        if (url.isBlank()) {
            return "http://localhost:8090/v1/chat-messages";
        }
        String normalized = url.trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("/chat-messages")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            return normalized + "v1/chat-messages";
        }
        return normalized + "/v1/chat-messages";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String describeError(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable root = rootCause(error);
        String message = firstNonBlank(root.getMessage(), error.getMessage());
        String type = root.getClass().getSimpleName();
        return message == null ? type : type + ": " + message;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
