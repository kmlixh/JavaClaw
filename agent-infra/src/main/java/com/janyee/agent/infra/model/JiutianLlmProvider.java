package com.janyee.agent.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolSchema;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class JiutianLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(JiutianLlmProvider.class);

    private final AgentPlatformProperties properties;
    private final LlmConfigService llmConfigService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public JiutianLlmProvider(
            AgentPlatformProperties properties,
            LlmConfigService llmConfigService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.llmConfigService = llmConfigService;
        this.objectMapper = objectMapper;
        // 同 OpenAiCompatibleLlmProvider 的 idle timeout 策略。九天 thinking / tool-call 阶段
        // 经常长时间无输出,读超时给到 300s (5 min);写超时 120s 留余量。
        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(120))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(300, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(120, TimeUnit.SECONDS)));
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
        Flux<LlmStreamEvent> upstream = llm.stream()
                ? requestStreamingCompletion(request, llm).flatMapIterable(this::mapStreamChunkToEvents)
                : requestCompletion(request, llm).flatMapMany(this::mapResponseToEvents);

        return upstream.onErrorResume(error -> {
            String errorDetail = describeError(error);
            log.warn("jiutian provider failed for config {}: {}", llm.configId(), errorDetail, error);
            return Flux.error(new IllegalStateException(
                    "Configured LLM request failed for " + llm.displayName() + ": " + errorDetail,
                    error
            ));
        });
    }

    private Mono<String> requestCompletion(LlmChatRequest request, LlmConfigDescriptor llm) {
        Map<String, Object> payload = basePayload(request, llm, llm.stream());
        return webClient.post()
                .uri(resolveEndpoint(llm))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, llm))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class);
    }

    private Flux<ServerSentEvent<String>> requestStreamingCompletion(LlmChatRequest request, LlmConfigDescriptor llm) {
        Map<String, Object> payload = basePayload(request, llm, true);
        return webClient.post()
                .uri(resolveEndpoint(llm))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> applyAuth(headers, llm))
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
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
            JsonNode toolCalls = firstChoice.path("message").path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode firstToolCall = toolCalls.get(0);
                String payload = objectMapper.writeValueAsString(Map.of(
                        "id", firstToolCall.path("id").asText(""),
                        "name", firstToolCall.path("function").path("name").asText(""),
                        "arguments", firstToolCall.path("function").path("arguments").asText("{}")
                ));
                return Flux.just(
                        new LlmStreamEvent("tool_call_request", payload),
                        new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("tool_calls"))
                );
            }

            if (!content.isBlank()) {
                return Flux.just(
                        new LlmStreamEvent("token", content),
                        new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("stop"))
                );
            }
            return Flux.just(new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("stop")));
        } catch (Exception error) {
            return Flux.just(new LlmStreamEvent("error", "invalid llm response: " + error.getMessage()));
        }
    }

    private List<LlmStreamEvent> mapStreamChunkToEvents(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) {
            return List.of();
        }
        if ("[DONE]".equals(data.trim())) {
            return List.of(new LlmStreamEvent("finish", "done"));
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return List.of();
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.path("delta");
            String content = delta.path("content").asText("");
            if (content.isBlank()) {
                content = firstChoice.path("message").path("content").asText("");
            }
            String finishReason = firstChoice.path("finish_reason").asText("");
            JsonNode toolCalls = delta.path("tool_calls");

            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode firstToolCall = toolCalls.get(0);
                Map<String, Object> payload = new HashMap<>();
                payload.put("index", firstToolCall.path("index").asInt(0));
                if (firstToolCall.hasNonNull("id")) {
                    payload.put("id", firstToolCall.path("id").asText());
                }
                JsonNode function = firstToolCall.path("function");
                if (function.hasNonNull("name")) {
                    payload.put("name", function.path("name").asText());
                }
                if (function.hasNonNull("arguments")) {
                    payload.put("arguments", function.path("arguments").asText());
                }
                if (!finishReason.isBlank()) {
                    return List.of(
                            new LlmStreamEvent("tool_call_delta", objectMapper.writeValueAsString(payload)),
                            new LlmStreamEvent("finish", finishReason)
                    );
                }
                return List.of(new LlmStreamEvent("tool_call_delta", objectMapper.writeValueAsString(payload)));
            }

            if (!content.isBlank() && !finishReason.isBlank()) {
                return List.of(
                        new LlmStreamEvent("token", content),
                        new LlmStreamEvent("finish", finishReason)
                );
            }
            if (!content.isBlank()) {
                return List.of(new LlmStreamEvent("token", content));
            }
            if (!finishReason.isBlank()) {
                return List.of(new LlmStreamEvent("finish", finishReason));
            }
            return List.of();
        } catch (Exception error) {
            return List.of(new LlmStreamEvent("error", "invalid llm stream chunk: " + error.getMessage()));
        }
    }

    private Map<String, Object> basePayload(LlmChatRequest request, LlmConfigDescriptor llm, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", defaultIfBlank(request.model(), llm.model()));
        payload.put("stream", stream);
        payload.put("auditSwitch", true);
        payload.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream().map(this::toToolDefinition).toList());
        }
        return payload;
    }

    private Map<String, Object> toToolDefinition(ToolSchema schema) {
        try {
            Object parameters = objectMapper.readValue(schema.jsonSchema(), Object.class);
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", schema.name(),
                            "description", schema.description(),
                            "parameters", parameters
                    )
            );
        } catch (Exception error) {
            throw new IllegalStateException("invalid tool schema for " + schema.name(), error);
        }
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
                            defaultIfBlank(llm.provider(), "jiutian"),
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
            return "https://jiutian.10086.cn/largemodel/moma/api/v3/chat/completions";
        }
        String normalized = url.trim();
        if (normalized.toLowerCase().contains("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            return normalized + "chat/completions";
        }
        return normalized + "/chat/completions";
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
        if (root instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                message = firstNonBlank(message, "") + " | responseBody=" + body;
            }
        }
        if (message == null) {
            return type;
        }
        return type + ": " + message;
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
