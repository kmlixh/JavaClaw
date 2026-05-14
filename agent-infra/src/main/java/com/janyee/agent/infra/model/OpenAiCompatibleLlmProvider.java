package com.janyee.agent.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.model.LlmConfigDescriptor;
import com.janyee.agent.runtime.model.LlmConfigService;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);

    private final AgentPlatformProperties properties;
    private final LlmConfigService llmConfigService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public OpenAiCompatibleLlmProvider(
            AgentPlatformProperties properties,
            LlmConfigService llmConfigService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.llmConfigService = llmConfigService;
        this.objectMapper = objectMapper;
        // responseTimeout 只覆盖 request -> 首字节;流体本身没管。SSE 中途断流但不关连接,
        // reactor-netty 要等 TCP keepalive 兜底才能发现,现场卡过几分钟。加 ReadTimeoutHandler
        // 作为 per-chunk idle timeout。
        //
        // 读超时给到 300s (5 min):LLM thinking / tool-call 阶段长时间无输出是正常的,给足
        // 余量避免"还在思考就被当成死链路掐掉"。写超时 120s 是读超时的对称余量 —— 发请求阶段
        // 理论上用不了这么久,但给大 prompt + 慢网络留缓冲。
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

        return upstream
                .onErrorResume(error -> {
                    String errorDetail = describeError(error);
                    log.warn("openai-compatible provider failed for config {}: {}", llm.configId(), errorDetail, error);
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
            // 非流式 usage 在 root 一级,跟 choices 同层。先抓出来,后面跟其他 events
            // 一起发(orchestrator 累计每轮 usage 用)。choices 为空仍然是错误,但 usage 单独存在。
            LlmStreamEvent usage = extractUsageEvent(root);
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
                return appendUsage(Flux.just(
                        new LlmStreamEvent("tool_call_request", payload),
                        new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("tool_calls"))
                ), usage);
            }

            if (!content.isBlank()) {
                return appendUsage(Flux.just(
                        new LlmStreamEvent("token", content),
                        new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("stop"))
                ), usage);
            }
            return appendUsage(Flux.just(new LlmStreamEvent("finish", firstChoice.path("finish_reason").asText("stop"))), usage);
        } catch (Exception error) {
            return Flux.just(new LlmStreamEvent("error", "invalid llm response: " + error.getMessage()));
        }
    }

    private Flux<LlmStreamEvent> appendUsage(Flux<LlmStreamEvent> base, LlmStreamEvent usage) {
        return usage == null ? base : base.concatWith(Flux.just(usage));
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
            // OpenAI 流式 usage 在最后一帧:choices=[],usage={...}。开 stream_options.include_usage
            // 才会下发。choices 非空时也可能挂带 usage(部分供应商提前给),所以两边都检查。
            LlmStreamEvent usage = extractUsageEvent(root);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return usage == null ? List.of() : List.of(usage);
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.path("delta");
            String content = delta.path("content").asText("");
            if (content.isBlank()) {
                content = firstChoice.path("message").path("content").asText("");
            }
            // o1 / DeepSeek-R1 / Qwen-thinking 等推理模型在 delta 上额外吐 reasoning_content,
            // 它跟 content 是独立通道,代表"思考链"。前端 / orchestrator 单独处理:不要混进
            // fullText 里(否则会被当成普通输出 echo 给用户),也不要丢掉(用户希望看到推理过程)。
            String reasoningContent = delta.path("reasoning_content").asText("");
            if (reasoningContent.isBlank()) {
                reasoningContent = firstChoice.path("message").path("reasoning_content").asText("");
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

            // thinking 跟 content 可能同时出现(reasoning 模型边想边产中间结论),也可能各自单独
            // 出现。先 emit thinking,再 emit token,顺序更接近"模型先思考再说话"的语义。
            List<LlmStreamEvent> out = new java.util.ArrayList<>();
            if (!reasoningContent.isBlank()) {
                out.add(new LlmStreamEvent("thinking", reasoningContent));
            }
            if (!content.isBlank()) {
                out.add(new LlmStreamEvent("token", content));
            }
            if (!finishReason.isBlank()) {
                out.add(new LlmStreamEvent("finish", finishReason));
            }
            if (usage != null) {
                out.add(usage);
            }
            return out;
        } catch (Exception error) {
            return List.of(new LlmStreamEvent("error", "invalid llm stream chunk: " + error.getMessage()));
        }
    }

    /**
     * 抽 usage 字段成 LlmStreamEvent("usage", {...} JSON)。供应商不返 usage / 字段缺失返 null。
     * 三个数字以 "prompt"/"completion"/"total" key 输出,跟 session_message 表的列名对齐方便累加。
     */
    private LlmStreamEvent extractUsageEvent(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            return null;
        }
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(0);
        if (prompt == 0 && completion == 0 && total == 0) {
            return null;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "prompt", prompt,
                    "completion", completion,
                    "total", total
            ));
            return new LlmStreamEvent("usage", payload);
        } catch (Exception error) {
            return null;
        }
    }

    private Map<String, Object> basePayload(LlmChatRequest request, LlmConfigDescriptor llm, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", defaultIfBlank(request.model(), llm.model()));
        payload.put("stream", stream);
        if (stream) {
            // OpenAI 标准:开 include_usage 后,流式响应最后一帧的 choices=[] + 带 usage,
            // 让我们能拿到 prompt/completion/total tokens。Dify/SiliconFlow/Moma 等兼容端
            // 是否真实现取决于供应商,不实现也不会报错(他们多半就当未知字段忽略)。
            payload.put("stream_options", Map.of("include_usage", true));
        }
        payload.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream().map(this::toToolDefinition).toList());
            payload.put("tool_choice", "auto");
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
            return "http://localhost:11434/v1/chat/completions";
        }
        String normalized = url.trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("/chat/completions")) {
            return normalized;
        }
        if (lower.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        if (lower.endsWith("/v1/")) {
            return normalized + "chat/completions";
        }
        if (normalized.endsWith("/")) {
            return normalized + "v1/chat/completions";
        }
        return normalized + "/v1/chat/completions";
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
        if ("ClosedChannelException".equals(type)) {
            return "SSL/TLS handshake was closed before completion. Check local proxy/VPN/TUN interception, outbound HTTPS access, or certificate trust";
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
