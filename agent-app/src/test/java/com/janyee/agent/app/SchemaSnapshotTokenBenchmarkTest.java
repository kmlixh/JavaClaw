package com.janyee.agent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.infra.tool.DatabaseSchemaInspectTool;
import com.janyee.agent.runtime.loop.ToolCallDecision;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolCallRequest;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.runtime.loop.ToolResultAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基准测试：构造一张 69 列 mock 表，对比精简前后给 LLM 的 schema context 字符数。
 *
 * 前后对比标杆（硬编码重构前的输出格式）：
 *   - 旧 snapshot 每表固定 7 行模板 + 全列名 + Strict rule 三条 —— 约 2.1x 当前紧凑版长度
 *   - 旧 dataJson 每列 13 字段 JSON object —— 约 2.4x 当前 5 字段版长度
 */
@SpringBootTest
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaSnapshotTokenBenchmarkTest {

    private static final String MOCK_TABLE = "xmap.layer_wy_5g_cell_info_section_yn";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseSchemaInspectTool schemaInspectTool;

    @Autowired
    private ToolResultAppender toolResultAppender;

    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUp() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xmap");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MOCK_TABLE);
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + MOCK_TABLE + " (cell_id serial PRIMARY KEY");
        // 模拟 68 个业务列（加上 pk 共 69）
        String[] colTypes = {"text", "numeric", "varchar(32)", "timestamp", "geometry(Geometry, 4326)", "int"};
        for (int i = 0; i < 68; i++) {
            String type = colTypes[i % colTypes.length];
            ddl.append(", col_").append(i).append(' ').append(type);
        }
        ddl.append(")");
        jdbcTemplate.execute(ddl.toString());
    }

    @AfterAll
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + MOCK_TABLE);
    }

    @Test
    void compactSchemaContextIsUnderTokenBudget() throws Exception {
        ToolResult result = schemaInspectTool.execute(new ToolInvocation(
                "dev-agent",
                "run-bench-" + UUID.randomUUID(),
                "sess-bench-" + UUID.randomUUID(),
                "junit",
                "db.schema.inspect",
                objectMapper.writeValueAsString(java.util.Map.of("table", MOCK_TABLE))
        ));
        assertTrue(result.ok(), "schema inspect must succeed on mock table: " + result.error());

        ToolCallRequest request = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "db.schema.inspect",
                objectMapper.writeValueAsString(java.util.Map.of("table", MOCK_TABLE)),
                ""
        );
        ToolCallDecision decision = new ToolCallDecision(true, false, "allowed", "db.schema.inspect",
                request.argumentsJson());
        ToolCallOutcome outcome = new ToolCallOutcome(request, decision, true, true, result, null, 0L);

        RunRequest runRequest = new RunRequest(
                "run-bench-" + UUID.randomUUID(),
                "sess-bench-" + UUID.randomUUID(),
                "dev-agent",
                "junit",
                "benchmark schema context size",
                false, null, null, List.of(), List.of()
        );
        ToolLoopContext context = new ToolLoopContext(
                runRequest,
                runRequest.runId(),
                new PromptContext("system", "user"),
                100
        );
        toolResultAppender.append(context, outcome);

        String schemaContext = context.schemaContext();
        assertNotNull(schemaContext, "schema context should be populated");
        assertFalse(schemaContext.isBlank());

        // 断言 1：新格式每表只占一小段（Table + cols + optional pk + hint），字符数 < 4000（单表 69 列）
        int snapshotChars = schemaContext.length();
        assertTrue(snapshotChars < 4000,
                "compact schema snapshot should stay under 4k chars, got " + snapshotChars
                        + "\n--snapshot--\n" + schemaContext);

        // 断言 2：紧凑格式必须带类型（name:TYPE）
        assertTrue(schemaContext.contains("cell_id:"),
                "snapshot must include column types. actual:\n" + schemaContext);
        assertTrue(schemaContext.contains("Table: " + MOCK_TABLE),
                "snapshot must carry qualified table name");

        // 断言 3：去掉每表重复的 Strict rule（改由 currentPrompt() 统一追加）
        assertFalse(schemaContext.contains("Strict rule"),
                "per-table Strict rule must be removed; rules belong to currentPrompt header now");

        // 断言 4：currentPrompt() 必须统一追加 Schema Usage Rules 一次
        String prompt = context.currentPrompt();
        int ruleOccurrences = countOccurrences(prompt, "[Schema Usage Rules]");
        assertTrue(ruleOccurrences == 1,
                "Schema Usage Rules should appear exactly once in currentPrompt, got " + ruleOccurrences);

        // 断言 5：dataJson 的 columns 从 13 字段降到 5
        JsonNode data = objectMapper.readTree(result.dataJson());
        JsonNode columns = data.path("columns");
        assertTrue(columns.isArray() && columns.size() == 5,
                "dataJson columns header should be 5 fields, got " + columns);

        // 断言 6：dataJson 的 rows 也只带 5 个业务字段（+ 不能包含被移除字段）
        JsonNode firstRow = data.path("rows").get(0);
        assertNotNull(firstRow, "rows[0] expected");
        assertFalse(firstRow.has("jdbc_type"), "jdbc_type must be trimmed");
        assertFalse(firstRow.has("column_size"), "column_size must be trimmed");
        assertFalse(firstRow.has("decimal_digits"), "decimal_digits must be trimmed");
        assertFalse(firstRow.has("auto_increment"), "auto_increment must be trimmed");
        assertFalse(firstRow.has("generated_column"), "generated_column must be trimmed");
        assertFalse(firstRow.has("default_value"), "default_value must be trimmed");
        assertFalse(firstRow.has("ordinal_position"), "ordinal_position must be trimmed");
        assertFalse(firstRow.has("type_name"), "type_name must be trimmed (subsumed by data_type)");

        assertTrue(firstRow.has("column_name"));
        assertTrue(firstRow.has("data_type"));
        assertTrue(firstRow.has("nullable"));
        assertTrue(firstRow.has("is_primary_key"));
        assertTrue(firstRow.has("remarks"));

        // 基准数据打印：便于后续回归对照
        System.out.println("[benchmark] snapshot chars = " + snapshotChars);
        System.out.println("[benchmark] dataJson chars = " + result.dataJson().length());
        System.out.println("[benchmark] snapshot:\n" + schemaContext);
    }

    private int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
