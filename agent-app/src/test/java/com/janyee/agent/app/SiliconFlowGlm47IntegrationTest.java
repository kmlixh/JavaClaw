package com.janyee.agent.app;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.api.ChatContextReference;
import com.janyee.agent.api.LlmConfigResponse;
import com.janyee.agent.api.RunDetailResponse;
import com.janyee.agent.api.SessionDetailResponse;
import com.janyee.agent.api.SessionMessageResponse;
import com.janyee.agent.api.ToolAuditLogResponse;
import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import com.janyee.agent.infra.persistence.entity.KnowledgeEntryEntity;
import com.janyee.agent.infra.persistence.repository.LlmProviderConfigRepository;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
class SiliconFlowGlm47IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LlmProviderConfigRepository llmProviderConfigRepository;

    @Autowired
    private KnowledgeEntryRepository knowledgeEntryRepository;

    private WebClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void shouldUseDatabaseConfiguredSiliconFlowGlm47() {
        LlmProviderConfigEntity config = llmProviderConfigRepository.findById("siliconflow-glm47")
                .orElseThrow(() -> new IllegalStateException("siliconflow-glm47 missing from database"));

        assertTrue(config.isEnabled());
        assertEquals("Pro/zai-org/GLM-4.7", config.getModel());
        assertNotNull(config.getApiKey());
        assertFalse(config.getApiKey().isBlank());

        List<LlmConfigResponse> llms = webClient.get()
                .uri("/api/llms")
                .retrieve()
                .bodyToFlux(LlmConfigResponse.class)
                .collectList()
                .block();

        assertNotNull(llms);
        assertTrue(llms.stream().anyMatch(llm -> "siliconflow-glm47".equals(llm.configId())));

        String sessionId = "sf-test-" + UUID.randomUUID();
        ChatSendResponse accepted = webClient.post()
                .uri("/api/chat/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatSendRequest(
                        sessionId,
                        "dev-agent",
                        "junit-user",
                        "siliconflow-glm47",
                        "请用中文简短回复“GLM-4.7集成测试通过”，不要输出其他内容。",
                        java.util.List.of(),
                        java.util.List.of()
                ))
                .retrieve()
                .bodyToMono(ChatSendResponse.class)
                .block();

        assertNotNull(accepted);
        assertEquals("siliconflow-glm47", accepted.llmConfigId());
        assertEquals("Pro/zai-org/GLM-4.7", accepted.llmModel());

        String streamBody = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                        .queryParam("runId", accepted.runId())
                        .queryParam("sessionId", accepted.sessionId())
                        .queryParam("agentId", accepted.agentId())
                        .queryParam("llmConfigId", accepted.llmConfigId())
                        .queryParam("userId", "junit-user")
                        .queryParam("message", "请用中文简短回复“GLM-4.7集成测试通过”，不要输出其他内容。")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertNotNull(streamBody);
        assertTrue(streamBody.contains("event:RUN_COMPLETED"));

        RunDetailResponse run = webClient.get()
                .uri("/api/runs/{id}", accepted.runId())
                .retrieve()
                .bodyToMono(RunDetailResponse.class)
                .block();

        assertNotNull(run);
        assertEquals("siliconflow-glm47", run.llmConfigId());
        assertEquals("Pro/zai-org/GLM-4.7", run.llmModel());

        SessionDetailResponse session = webClient.get()
                .uri("/api/sessions/{id}", accepted.sessionId())
                .retrieve()
                .bodyToMono(SessionDetailResponse.class)
                .block();

        assertNotNull(session);
        assertTrue(session.messages().stream().anyMatch(item -> "assistant".equals(item.role()) && item.content() != null && !item.content().isBlank()));
    }

    @Test
    void shouldListWorkspaceFilesForChineseInstruction() {
        String sessionId = "sf-files-" + UUID.randomUUID();

        ChatSendResponse accepted = webClient.post()
                .uri("/api/chat/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatSendRequest(
                        sessionId,
                        "dev-agent",
                        "junit-user",
                        "siliconflow-glm47",
                        "列出 workspace 根目录下可用文件",
                        java.util.List.of(),
                        java.util.List.of()
                ))
                .retrieve()
                .bodyToMono(ChatSendResponse.class)
                .block();

        assertNotNull(accepted);

        String streamBody = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                        .queryParam("runId", accepted.runId())
                        .queryParam("sessionId", accepted.sessionId())
                        .queryParam("agentId", accepted.agentId())
                        .queryParam("llmConfigId", accepted.llmConfigId())
                        .queryParam("userId", "junit-user")
                        .queryParam("message", "列出 workspace 根目录下可用文件")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertNotNull(streamBody);
        assertTrue(streamBody.contains("event:RUN_COMPLETED") || streamBody.contains("event:RUN_FAILED"));

        RunDetailResponse run = webClient.get()
                .uri("/api/runs/{id}", accepted.runId())
                .retrieve()
                .bodyToMono(RunDetailResponse.class)
                .block();

        assertNotNull(run);
        boolean calledFileList = run.toolAudits().stream()
                .map(ToolAuditLogResponse::toolName)
                .anyMatch("file.list"::equals);

        SessionDetailResponse session = webClient.get()
                .uri("/api/sessions/{id}", accepted.sessionId())
                .retrieve()
                .bodyToMono(SessionDetailResponse.class)
                .block();

        assertNotNull(session);
        boolean assistantHasFileHints = session.messages().stream()
                .filter(item -> "assistant".equals(item.role()))
                .map(item -> item.content() == null ? "" : item.content())
                .anyMatch(content ->
                        content.contains("AGENT.md")
                                || content.contains("SOUL.md")
                                || content.contains("MEMORY.md")
                                || content.contains("knowledge")
                                || content.contains("artifacts")
                );

        assertTrue(calledFileList, "GLM-4.7 did not call file.list for workspace listing request");
        assertTrue(assistantHasFileHints, "assistant response did not contain workspace file listing hints");
    }

    @Test
    void shouldAggregateAndRenderChartWithReferencedDatabaseKnowledge() {
        String sessionId = "sf-chart-" + UUID.randomUUID();
        String knowledgeId = "chart-db-" + UUID.randomUUID();

        KnowledgeEntryEntity knowledge = new KnowledgeEntryEntity();
        knowledge.setId(knowledgeId);
        knowledge.setAgentId("dev-agent");
        knowledge.setTitle("Postgres运行数据源");
        knowledge.setContent("""
                使用以下数据库连接信息执行查询：
                - dbType: postgresql
                - host: 10.173.108.120
                - port: 5433
                - database: java_claw
                - username: postgres
                - password: JL0Is1KqGuQMayeF

                当用户要求分组统计并展示成图表时：
                1. 先用 db.query 执行聚合 SQL
                2. 再用 chart.echarts 生成柱状图
                3. 不要输出 markdown 表格，只输出简短总结

                本次任务建议查询：
                select status, count(*) as total
                from run_record
                group by status
                order by total desc;
                """);
        knowledge.setContentType("markdown");
        knowledge.setSource("junit");
        knowledge.setTagsJson("[\"database\",\"chart\"]");
        knowledge.setEnabled(true);
        knowledge.setVersion(1);
        knowledgeEntryRepository.save(knowledge);

        try {
            ChatSendResponse accepted = webClient.post()
                    .uri("/api/chat/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new ChatSendRequest(
                            sessionId,
                            "dev-agent",
                            "junit-user",
                            "siliconflow-glm47",
                            "使用 {Postgres运行数据源}，按 run_record 的 status 分组统计数量，并用柱状图展示结果。",
                            List.of(new ChatContextReference("knowledge", knowledgeId, "Postgres运行数据源")),
                            List.of()
                    ))
                    .retrieve()
                    .bodyToMono(ChatSendResponse.class)
                    .block();

            assertNotNull(accepted);

            String streamBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                            .queryParam("runId", accepted.runId())
                            .queryParam("sessionId", accepted.sessionId())
                            .queryParam("agentId", accepted.agentId())
                            .queryParam("llmConfigId", accepted.llmConfigId())
                            .queryParam("userId", "junit-user")
                            .queryParam("message", "使用 {Postgres运行数据源}，按 run_record 的 status 分组统计数量，并用柱状图展示结果。")
                            .build())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            assertNotNull(streamBody);
            assertTrue(streamBody.contains("event:RUN_COMPLETED") || streamBody.contains("event:RUN_FAILED"));

            RunDetailResponse run = webClient.get()
                    .uri("/api/runs/{id}", accepted.runId())
                    .retrieve()
                    .bodyToMono(RunDetailResponse.class)
                    .block();

            assertNotNull(run);
            assertTrue(run.toolAudits().stream().map(ToolAuditLogResponse::toolName).anyMatch("db.query"::equals),
                    "chart flow did not call db.query");
            assertTrue(run.toolAudits().stream().map(ToolAuditLogResponse::toolName).anyMatch("chart.echarts"::equals),
                    "chart flow did not call chart.echarts");

            SessionDetailResponse session = webClient.get()
                    .uri("/api/sessions/{id}", accepted.sessionId())
                    .retrieve()
                    .bodyToMono(SessionDetailResponse.class)
                    .block();

            assertNotNull(session);
            SessionMessageResponse chartMessage = session.messages().stream()
                    .filter(item -> "tool".equals(item.role()) && "chart.echarts".equals(item.toolName()))
                    .max(Comparator.comparing(SessionMessageResponse::id))
                    .orElse(null);

            assertNotNull(chartMessage, "session transcript did not persist chart tool result");
            assertNotNull(chartMessage.toolResultJson());
            assertTrue(chartMessage.toolResultJson().contains("\"displayType\":\"echarts\""),
                    "chart tool result did not contain echarts payload");
            assertTrue(session.messages().stream()
                    .filter(item -> "assistant".equals(item.role()))
                    .map(SessionMessageResponse::content)
                    .filter(content -> content != null && !content.isBlank())
                    .anyMatch(content -> content.contains("柱状图") || content.contains("status") || content.contains("统计")),
                    "assistant summary did not describe grouped chart result");
        } finally {
            knowledgeEntryRepository.deleteById(knowledgeId);
        }
    }
}
