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
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端核对分区表元数据链路（后端不再自动 rewrite，由 AI 自己根据元数据写 WHERE 过滤）：
 * <ol>
 *   <li>{@link PartitionInspector#inspect} 能识别分区父表，并暴露策略 / 键列 / 键类型 / earliest+latest bound；</li>
 *   <li>{@link DatabaseQueryTool} 不再做 partition rewrite —— {@code FROM parent} 透传给 PG，
 *       AI 写 {@code WHERE dt = '...'} 时 PG native pruning 落到对应 child；不写过滤就是全表扫；</li>
 *   <li>{@link DatabaseSchemaInspectTool} 在 {@code metadata.partition} 中暴露上述字段；</li>
 *   <li>对普通（非分区）表，所有路径保持 no-op。</li>
 * </ol>
 *
 * 测试自建两张样本表：
 *   - partitioning_test.events (parent) 按 dt RANGE 分区，附三个子分区；
 *   - partitioning_test.regular_facts（无分区）。
 * 所有建表/数据都在类生命周期里 teardown —— 不污染 java_claw 正式数据。
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartitionInspectorTest {

    private static final String TEST_SCHEMA = "partitioning_test";
    private static final String PARENT_TABLE = "events";
    private static final String REGULAR_TABLE = "regular_facts";
    // 三个子分区按 dt 升序，名字后缀是 YYYY_MM_DD —— 名字字典序 DESC 就是时间倒序。
    private static final String OLDEST_CHILD = "events_partition_2026_01_01";
    private static final String MIDDLE_CHILD = "events_partition_2026_02_01";
    private static final String LATEST_CHILD = "events_partition_2026_03_01";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseQueryTool databaseQueryTool;

    @Autowired
    private DatabaseSchemaInspectTool databaseSchemaInspectTool;

    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUp() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA);

        // 父表 + 三个子分区
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_SCHEMA + "." + PARENT_TABLE + " CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE %1$s.%2$s (
                    id bigserial,
                    dt timestamp NOT NULL,
                    payload text
                ) PARTITION BY RANGE (dt)
                """.formatted(TEST_SCHEMA, PARENT_TABLE));
        jdbcTemplate.execute("""
                CREATE TABLE %1$s.%3$s PARTITION OF %1$s.%2$s
                    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')
                """.formatted(TEST_SCHEMA, PARENT_TABLE, OLDEST_CHILD));
        jdbcTemplate.execute("""
                CREATE TABLE %1$s.%3$s PARTITION OF %1$s.%2$s
                    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01')
                """.formatted(TEST_SCHEMA, PARENT_TABLE, MIDDLE_CHILD));
        jdbcTemplate.execute("""
                CREATE TABLE %1$s.%3$s PARTITION OF %1$s.%2$s
                    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01')
                """.formatted(TEST_SCHEMA, PARENT_TABLE, LATEST_CHILD));

        // 每个子分区塞一条可辨识的数据，用来验证查询命中的是哪个分区
        jdbcTemplate.update("INSERT INTO " + TEST_SCHEMA + "." + PARENT_TABLE + " (dt, payload) VALUES (?, ?)",
                java.sql.Timestamp.valueOf("2026-01-15 00:00:00"), "jan-oldest");
        jdbcTemplate.update("INSERT INTO " + TEST_SCHEMA + "." + PARENT_TABLE + " (dt, payload) VALUES (?, ?)",
                java.sql.Timestamp.valueOf("2026-02-15 00:00:00"), "feb-middle");
        jdbcTemplate.update("INSERT INTO " + TEST_SCHEMA + "." + PARENT_TABLE + " (dt, payload) VALUES (?, ?)",
                java.sql.Timestamp.valueOf("2026-03-15 00:00:00"), "mar-latest");

        // 普通表 + 一条数据
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_SCHEMA + "." + REGULAR_TABLE);
        jdbcTemplate.execute("""
                CREATE TABLE %s.%s (
                    id bigserial PRIMARY KEY,
                    label text
                )""".formatted(TEST_SCHEMA, REGULAR_TABLE));
        jdbcTemplate.update(
                "INSERT INTO " + TEST_SCHEMA + "." + REGULAR_TABLE + " (label) VALUES (?)",
                "only-row");

        PartitionInspector.invalidateCache();
    }

    @AfterAll
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_SCHEMA + "." + PARENT_TABLE + " CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_SCHEMA + "." + REGULAR_TABLE);
        PartitionInspector.invalidateCache();
    }

    @Test
    void inspectorDetectsParentAndPicksLatestChild() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PartitionInspector.PartitionInfo info = PartitionInspector.inspect(
                    connection, connection.getMetaData().getURL(), TEST_SCHEMA, PARENT_TABLE);

            assertTrue(info.isPartitioned(), "events 父表应被识别为分区表");
            assertEquals("dt", info.partitionKey(), "分区键应指向 dt 列");
            assertEquals("timestamp", info.partitionKeyType(), "分区键类型应为 timestamp");
            assertEquals("range", info.partitionStrategy(), "events 是 RANGE 分区");
            assertEquals(TEST_SCHEMA + "." + LATEST_CHILD, info.latestPartition(),
                    "按 relname DESC 取到的应当是最新命名的子分区");
            assertEquals(TEST_SCHEMA + "." + OLDEST_CHILD, info.earliestPartition(),
                    "按 relname ASC 取到的应当是最早命名的子分区");
            assertEquals(3, info.childCount(), "目前挂载了 3 个子分区");
            assertNotNull(info.latestPartitionBound());
            assertTrue(info.latestPartitionBound().contains("2026-03-01"),
                    "最新分区的边界应包含 2026-03-01，实际=" + info.latestPartitionBound());
            assertTrue(info.earliestPartitionBound().contains("2026-01-01"),
                    "最早分区的边界应包含 2026-01-01，实际=" + info.earliestPartitionBound());
        }
    }

    @Test
    void inspectorReportsRegularForNonPartitionedTable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PartitionInspector.PartitionInfo info = PartitionInspector.inspect(
                    connection, connection.getMetaData().getURL(), TEST_SCHEMA, REGULAR_TABLE);
            assertFalse(info.isPartitioned(), "regular_facts 不应被识别为分区表");
            assertTrue(info.latestPartition().isBlank(),
                    "非分区表的 latestPartition 必须为空，避免后续 SQL 被错误重写");
            assertEquals(0, info.childCount());
        }
    }

    @Test
    void dbQueryOnPartitionedParentWithoutFilterScansAllChildren() throws Exception {
        PartitionInspector.invalidateCache();

        // 不写 WHERE 过滤分区键 —— 后端不再自动 rewrite，SQL 原样下发给 PG，三个子分区都会被扫到。
        String sql = "SELECT payload FROM " + TEST_SCHEMA + "." + PARENT_TABLE + " ORDER BY dt";
        String args = objectMapper.writeValueAsString(Map.of("sql", sql));

        ToolResult result = databaseQueryTool.execute(new ToolInvocation(
                "dev-agent", "partition-test-run", "partition-test-sess", "junit", "db.query", args));
        assertTrue(result.ok(), "partitioned query should succeed: " + result.error());
        assertFalse(result.summary().contains("rewritten to latest partition"),
                "后端已下线 partition rewrite，summary 不应再出现 'rewritten to latest partition'，实际=" + result.summary());

        JsonNode data = objectMapper.readTree(result.dataJson());
        assertTrue(data.path("partitionRewrite").isMissingNode(),
                "partitionRewrite 字段已彻底移除");
        assertEquals(sql, data.path("sql").asText(), "SQL 应保持原样下发给 PG");
        JsonNode rows = data.path("rows");
        assertTrue(rows.isArray());
        assertEquals(3, rows.size(), "无 WHERE 过滤时全部子分区都应被读取，实际行数=" + rows.size());
    }

    @Test
    void dbQueryOnPartitionedParentWithKeyFilterPrunesToCorrectChild() throws Exception {
        PartitionInspector.invalidateCache();

        // 写 WHERE 过滤分区键 —— PG native partition pruning 自然落到 2026-02 那一个 child。
        String sql = "SELECT payload FROM " + TEST_SCHEMA + "." + PARENT_TABLE
                + " WHERE dt >= '2026-02-01' AND dt < '2026-03-01'";
        String args = objectMapper.writeValueAsString(Map.of("sql", sql));

        ToolResult result = databaseQueryTool.execute(new ToolInvocation(
                "dev-agent", "partition-prune-run", "partition-prune-sess", "junit", "db.query", args));
        assertTrue(result.ok(), "filtered query should succeed: " + result.error());

        JsonNode data = objectMapper.readTree(result.dataJson());
        assertEquals(sql, data.path("sql").asText(), "SQL 不应被改写");
        JsonNode rows = data.path("rows");
        assertEquals(1, rows.size(), "WHERE 命中 2026-02 那一行，应只有 1 行，实际=" + rows);
        assertEquals("feb-middle", rows.get(0).path("payload").asText(),
                "应命中 middle 子分区那条数据");
    }

    @Test
    void dbQueryOnRegularTableIsNotRewritten() throws Exception {
        PartitionInspector.invalidateCache();

        String sql = "SELECT label FROM " + TEST_SCHEMA + "." + REGULAR_TABLE;
        String args = objectMapper.writeValueAsString(Map.of("sql", sql));

        ToolResult result = databaseQueryTool.execute(new ToolInvocation(
                "dev-agent", "partition-test-run", "partition-test-sess", "junit", "db.query", args));
        assertTrue(result.ok(), "regular query should succeed: " + result.error());
        assertFalse(result.summary().contains("rewritten to latest partition"),
                "summary 绝对不能出现 partition rewrite 文案，实际=" + result.summary());

        JsonNode data = objectMapper.readTree(result.dataJson());
        assertTrue(data.path("partitionRewrite").isMissingNode(),
                "partitionRewrite 字段已彻底移除");
        assertEquals(sql, data.path("sql").asText(), "SQL 应保持原样");
    }

    @Test
    void schemaInspectSurfacesPartitionMetadata() throws Exception {
        PartitionInspector.invalidateCache();

        String args = objectMapper.writeValueAsString(Map.of(
                "table", PARENT_TABLE,
                "schema", TEST_SCHEMA,
                "forceRefresh", true
        ));
        ToolResult result = databaseSchemaInspectTool.execute(new ToolInvocation(
                "dev-agent", "partition-inspect-run", "sess", "junit", "db.schema.inspect", args));
        assertTrue(result.ok(), "schema inspect 应成功: " + result.error());
        assertTrue(result.summary().contains("partitioned"),
                "schema inspect summary 应注明分区信息，实际=" + result.summary());
        assertTrue(result.summary().contains(LATEST_CHILD),
                "summary 应包含 latest child 名，实际=" + result.summary());
        assertTrue(result.summary().contains(OLDEST_CHILD),
                "summary 应包含 earliest child 名（数据可查的最早范围），实际=" + result.summary());
        assertTrue(result.summary().contains("range"),
                "summary 应注明分区策略 range，实际=" + result.summary());

        JsonNode data = objectMapper.readTree(result.dataJson());
        JsonNode partition = data.path("metadata").path("partition");
        assertTrue(partition.isObject(), "metadata.partition 应为对象");
        assertTrue(partition.path("isPartitioned").asBoolean(), "isPartitioned 必须为 true");
        assertEquals("dt", partition.path("partitionKey").asText());
        assertEquals("timestamp", partition.path("partitionKeyType").asText(),
                "AI 据此区分时间分区和数值分区");
        assertEquals("range", partition.path("partitionStrategy").asText());
        assertEquals(TEST_SCHEMA + "." + LATEST_CHILD, partition.path("latestPartition").asText());
        assertEquals(TEST_SCHEMA + "." + OLDEST_CHILD, partition.path("earliestPartition").asText());
        assertTrue(partition.path("earliestPartitionBound").asText().contains("2026-01-01"));
        assertTrue(partition.path("latestPartitionBound").asText().contains("2026-03-01"));
        assertEquals(3, partition.path("childCount").asInt());
    }

    @Test
    void schemaInspectForRegularTableMarksIsPartitionedFalse() throws Exception {
        PartitionInspector.invalidateCache();

        String args = objectMapper.writeValueAsString(Map.of(
                "table", REGULAR_TABLE,
                "schema", TEST_SCHEMA,
                "forceRefresh", true
        ));
        ToolResult result = databaseSchemaInspectTool.execute(new ToolInvocation(
                "dev-agent", "partition-inspect-run-2", "sess", "junit", "db.schema.inspect", args));
        assertTrue(result.ok());

        JsonNode data = objectMapper.readTree(result.dataJson());
        JsonNode partition = data.path("metadata").path("partition");
        assertFalse(partition.path("isPartitioned").asBoolean(), "非分区表 isPartitioned 必须为 false");
        assertTrue(partition.path("latestPartition").asText().isBlank());
    }
}
