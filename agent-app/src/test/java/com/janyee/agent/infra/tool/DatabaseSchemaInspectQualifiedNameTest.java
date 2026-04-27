package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证两件事：
 *   1) 带 schema 的表名（"xmap_ott.layer_yunnan_5g_weak_coverage_region" 风格）能被正确解析并 resolve
 *   2) 如果目标表不存在于 fallback datasource，返回的错误消息显式指出 fell back + 建议补 jdbcUrl
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseSchemaInspectQualifiedNameTest {

    @Autowired
    private DatabaseSchemaInspectTool tool;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() {
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS xmap_ott");
        jdbc.execute("DROP TABLE IF EXISTS xmap_ott.qn_probe");
        jdbc.execute("CREATE TABLE xmap_ott.qn_probe ("
                + "region_id bigint primary key, grid_count int, area_m2 double precision)");
    }

    @AfterAll
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS xmap_ott.qn_probe");
    }

    @Test
    void qualifiedNameWithUnderscoreSchemaResolvesCorrectly() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "table", "xmap_ott.qn_probe"
        ));
        ToolResult r = tool.execute(new ToolInvocation(
                "dev-agent", "run-qn-" + UUID.randomUUID(),
                "sess-qn-" + UUID.randomUUID(), "junit",
                "db.schema.inspect", payload));

        assertTrue(r.ok(), "schema inspect should succeed for xmap_ott.qn_probe: " + r.error());
        JsonNode data = objectMapper.readTree(r.dataJson());
        assertEquals("xmap_ott", data.path("metadata").path("schema").asText());
        assertEquals("qn_probe", data.path("metadata").path("table").asText());
        assertEquals("xmap_ott.qn_probe", data.path("metadata").path("qualifiedName").asText());
        assertTrue(r.summary().contains("xmap_ott.qn_probe"),
                "summary should carry the qualified name, got: " + r.summary());
    }

    @Test
    void fallbackModeNotFoundReturnsHelpfulMessage() throws Exception {
        // 用一个本地 java_claw 库里不存在的 xmap_ott 表名，模拟 LLM 漏传 jdbcUrl 场景
        String payload = objectMapper.writeValueAsString(Map.of(
                "table", "xmap_ott.layer_yunnan_5g_weak_coverage_region"
        ));
        ToolResult r = tool.execute(new ToolInvocation(
                "dev-agent", "run-fb-" + UUID.randomUUID(),
                "sess-fb-" + UUID.randomUUID(), "junit",
                "db.schema.inspect", payload));

        assertFalse(r.ok(), "should fail because this table does not exist in fallback datasource");
        JsonNode data = objectMapper.readTree(r.dataJson());
        assertTrue(data.path("fellBackToDefault").asBoolean(),
                "fellBackToDefault flag must be true when no jdbcUrl was provided");
        String msg = data.path("message").asText();
        assertNotNull(msg);
        // 指引文本必须出现三个关键提示：fallback / retry / jdbcUrl
        assertTrue(msg.contains("fallback"), "message must mention fallback: " + msg);
        assertTrue(msg.contains("jdbcUrl"), "message must mention jdbcUrl: " + msg);
        assertTrue(msg.contains("username") || msg.contains("password"),
                "message must hint credentials: " + msg);
    }
}
