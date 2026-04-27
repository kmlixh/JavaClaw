package com.janyee.agent.app;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.api.LlmConfigResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import com.janyee.agent.infra.persistence.repository.LlmProviderConfigRepository;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

final class FlowTestSupport {

    private FlowTestSupport() {
    }

    static ConfigurableApplicationContext startContext(String... profiles) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(AgentApplication.class)
                .properties(
                        "spring.main.web-application-type=reactive",
                        "server.port=0"
                );
        if (profiles != null && profiles.length > 0) {
            builder.profiles(profiles);
        }
        return builder.run();
    }

    static WebClient createClient(ConfigurableApplicationContext context) {
        ReactiveWebServerApplicationContext webContext = (ReactiveWebServerApplicationContext) context;
        int port = webContext.getWebServer().getPort();
        return WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    static void requireRouting(ConfigurableApplicationContext context) {
        AgentRouter agentRouter = context.getBean(AgentRouter.class);
        AgentBinding opsBinding = agentRouter.route(new AgentRouteRequest("web", "ops", "ops-smoke-session", null));
        require("ops-agent".equals(opsBinding.agentId()), "route binding to ops-agent failed");
    }

    static void seedLlmConfig(
            ConfigurableApplicationContext context,
            String id,
            String displayName,
            String model,
            String baseUrl,
            String apiKey,
            String chatPath,
            boolean streamEnabled,
            boolean defaultConfig
    ) {
        LlmProviderConfigRepository repository = context.getBean(LlmProviderConfigRepository.class);
        LlmProviderConfigEntity entity = repository.findById(id).orElseGet(LlmProviderConfigEntity::new);
        entity.setId(id);
        entity.setProvider("openai-compatible");
        entity.setDisplayName(displayName);
        entity.setModel(model);
        entity.setBaseUrl(baseUrl);
        entity.setApiKey(apiKey);
        entity.setChatPath(chatPath);
        entity.setStreamEnabled(streamEnabled);
        entity.setEnabled(true);
        entity.setDefaultConfig(defaultConfig);
        repository.save(entity);
    }

    static void deleteLlmConfig(ConfigurableApplicationContext context, String id) {
        context.getBean(LlmProviderConfigRepository.class).deleteById(id);
    }

    static LlmProviderConfigEntity requireLlmConfig(ConfigurableApplicationContext context, String id) {
        return context.getBean(LlmProviderConfigRepository.class).findById(id)
                .orElseThrow(() -> new IllegalStateException("llm config not found: " + id));
    }

    static void verifyHttpFlow(WebClient webClient, String llmConfigId, String expectedModel, String message) {
        String sessionId = "flow-" + UUID.randomUUID();
        List<LlmConfigResponse> llms = webClient.get()
                .uri("/api/llms")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<LlmConfigResponse>>() {
                })
                .block();
        require(llms != null && llms.stream().anyMatch(llm -> llmConfigId.equals(llm.configId())),
                "llm list did not expose expected config: " + llmConfigId);

        ChatSendResponse accepted = webClient.post()
                .uri("/api/chat/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatSendRequest(
                        sessionId,
                        "dev-agent",
                        "smoke-user",
                        llmConfigId,
                        expectedModel,
                        message,
                        java.util.List.of(),
                        java.util.List.of()
                ))
                .retrieve()
                .bodyToMono(ChatSendResponse.class)
                .block();
        require(accepted != null, "chat send returned null");
        require(sessionId.equals(accepted.sessionId()), "chat send returned unexpected session id");
        require("dev-agent".equals(accepted.agentId()), "chat send returned unexpected agent id");
        require(llmConfigId.equals(accepted.llmConfigId()), "chat send did not persist llm config id");
        require(expectedModel.equals(accepted.llmModel()), "chat send did not echo selected llm model");

        String streamBody = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                        .queryParam("runId", accepted.runId())
                        .queryParam("sessionId", accepted.sessionId())
                        .queryParam("agentId", accepted.agentId())
                        .queryParam("llmConfigId", accepted.llmConfigId())
                        .queryParam("llmModel", expectedModel)
                        .queryParam("userId", "smoke-user")
                        .queryParam("message", message)
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        require(streamBody != null && streamBody.contains("event:RUN_COMPLETED"), "stream did not complete successfully");
        require(streamBody.contains("event:RUN_STARTED"), "stream did not emit RUN_STARTED");

        RunDetailResponse run = webClient.get()
                .uri("/api/runs/{id}", accepted.runId())
                .retrieve()
                .bodyToMono(RunDetailResponse.class)
                .block();
        require(run != null, "run detail returned null");
        require(llmConfigId.equals(run.llmConfigId()), "run detail lost llm config id");
        require(expectedModel.equals(run.llmModel()), "run detail lost llm model");

        SessionDetailResponse session = webClient.get()
                .uri("/api/sessions/{id}", accepted.sessionId())
                .retrieve()
                .bodyToMono(SessionDetailResponse.class)
                .block();
        require(session != null, "session detail returned null");
        require("dev-agent".equals(session.agentId()), "session detail lost resolved agent id");
        require(session.messages().stream().anyMatch(item -> "assistant".equals(item.role()) && item.content() != null && !item.content().isBlank()),
                "session transcript did not include assistant message");
        require(session.messages().stream().anyMatch(item -> "user".equals(item.role()) && message.equals(item.content())),
                "session transcript did not include original user message");
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
