package com.janyee.agent.app;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.api.LlmConfigResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.infra.persistence.repository.LlmProviderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
class PostgresFlowIntegrationTest {
    private static final String TEST_LLM_ID = "siliconflow-glm47";

    @LocalServerPort
    private int port;

    @Autowired
    private LlmProviderConfigRepository llmProviderConfigRepository;

    private WebClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
        assertTrue(llmProviderConfigRepository.findById(TEST_LLM_ID).isPresent(), "siliconflow-glm47 missing from database");
    }

    @Test
    void shouldCompleteHttpFlowWithPostgresProfileAndSelectedLlm() {
        String sessionId = "pg-test-" + UUID.randomUUID();

        List<LlmConfigResponse> llms = webClient.get()
                .uri("/api/llms")
                .retrieve()
                .bodyToFlux(LlmConfigResponse.class)
                .collectList()
                .block();

        assertNotNull(llms);
        assertTrue(llms.stream().anyMatch(llm -> TEST_LLM_ID.equals(llm.configId())));

        ChatSendResponse accepted = webClient.post()
                .uri("/api/chat/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatSendRequest(
                        sessionId,
                        "dev-agent",
                        "junit-user",
                        TEST_LLM_ID,
                        "/tool echo postgres junit"
                ))
                .retrieve()
                .bodyToMono(ChatSendResponse.class)
                .block();

        assertNotNull(accepted);
        assertEquals(sessionId, accepted.sessionId());
        assertEquals("dev-agent", accepted.agentId());
        assertEquals(TEST_LLM_ID, accepted.llmConfigId());
        assertEquals("Pro/zai-org/GLM-4.7", accepted.llmModel());

        String streamBody = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                        .queryParam("runId", accepted.runId())
                        .queryParam("sessionId", accepted.sessionId())
                        .queryParam("agentId", accepted.agentId())
                        .queryParam("llmConfigId", accepted.llmConfigId())
                        .queryParam("userId", "junit-user")
                        .queryParam("message", "/tool echo postgres junit")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertNotNull(streamBody);
        assertTrue(streamBody.contains("event:RUN_STARTED"));
        assertTrue(streamBody.contains("event:RUN_COMPLETED"));

        RunDetailResponse run = webClient.get()
                .uri("/api/runs/{id}", accepted.runId())
                .retrieve()
                .bodyToMono(RunDetailResponse.class)
                .block();

        assertNotNull(run);
        assertEquals(TEST_LLM_ID, run.llmConfigId());
        assertEquals("Pro/zai-org/GLM-4.7", run.llmModel());

        SessionDetailResponse session = webClient.get()
                .uri("/api/sessions/{id}", accepted.sessionId())
                .retrieve()
                .bodyToMono(SessionDetailResponse.class)
                .block();

        assertNotNull(session);
        assertEquals("dev-agent", session.agentId());
        assertTrue(session.messages().stream().anyMatch(item -> "user".equals(item.role()) && "/tool echo postgres junit".equals(item.content())));
        assertTrue(session.messages().stream().anyMatch(item -> "assistant".equals(item.role()) && item.content() != null && !item.content().isBlank()));
    }
}
