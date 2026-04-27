package com.janyee.agent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.infra.tool.ArtifactMarkdownTool;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import com.janyee.agent.runtime.skill.SkillDefinitionService;
import com.janyee.agent.runtime.skill.SkillPrompt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端验证 skill.coverage.analysis：
 *  1) SkillDefinitionService 能读到 skill 并返回预期锚点
 *  2) SimplePromptAssembler 会把 skill prompt 注入 assembled prompt
 *  3) 从 skill prompt 中抽出的 SQL 模板在 postgis 环境下能针对 mock 表真实执行并返回合理结果
 *  4) ArtifactMarkdownTool 能把覆盖分析 markdown 持久化为 artifact
 */
@SpringBootTest
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageAnalysisSkillTest {

    private static final String AGENT_ID = "dev-agent";
    private static final String SKILL_NAME = "skill.coverage.analysis";
    private static final String TEST_DT = "2026-03";
    private static final String TEST_GEOMETRY_JSON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[102.5,24.5],[103.5,24.5],[103.5,25.5],[102.5,25.5],[102.5,24.5]]]}";

    // 严格按 skill prompt 的【涉及数据表】列出的全限定名 —— 测试中 mock 建表要一一对应
    private static final List<String> MOCK_TABLES = List.of(
            "xmap.layer_wy_4g_cell_info_section_yn",
            "xmap.layer_wy_5g_cell_info_section_yn",
            "xmap.layer_wy_2g_cell_info_section_yn",
            "xmap_deal.ott_yunnan_buildings",
            "xmap_ott.layer_yunnan_5g_weak_coverage_region",
            "xmap_ott.layer_yunnan_4g_weak_coverage_region",
            "xmap.layer_mdt_grid_nokia_50_month_weakarea",
            "ott_temp.yunnan_5g_grid_detail_ztest_all",
            "ott_temp.yunnan_4g_grid_detail_ztest_all",
            "xmap.layer_mdt_grid_nokia_50_info_week"
    );

    private static final Pattern SQL_BLOCK_PATTERN = Pattern.compile("```sql\\s*(.*?)```", Pattern.DOTALL);

    @Autowired
    private SkillDefinitionService skillDefinitionService;

    @Autowired
    private PromptAssembler promptAssembler;

    @Autowired
    private ArtifactMarkdownTool artifactMarkdownTool;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUpMockDatabase() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);

        // postgis 必须预先具备；若未装则跳过 SQL 执行相关测试
        Integer postgisCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'postgis'", Integer.class);
        if (postgisCount == null || postgisCount == 0) {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        }

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xmap");
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xmap_ott");
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xmap_deal");
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS ott_temp");

        // 扇区三张 + 楼宇：只含 geom / build_geom
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap.layer_wy_4g_cell_info_section_yn (
                    cell_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap.layer_wy_5g_cell_info_section_yn (
                    cell_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap.layer_wy_2g_cell_info_section_yn (
                    cell_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap_deal.ott_yunnan_buildings (
                    building_id serial PRIMARY KEY,
                    build_geom geometry(Geometry, 4326)
                )""");

        // 弱覆盖区域三张：含 grid_count + dt
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap_ott.layer_yunnan_5g_weak_coverage_region (
                    region_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326),
                    grid_count int,
                    dt varchar(16)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap_ott.layer_yunnan_4g_weak_coverage_region (
                    region_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326),
                    grid_count int,
                    dt varchar(16)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xmap.layer_mdt_grid_nokia_50_month_weakarea (
                    region_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326),
                    grid_count int,
                    dt varchar(16)
                )""");

        // 栅格三张：sample_cnt / coverage_rate / avg_rsrp / avg_sinr / dt
        String gridDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                    grid_id serial PRIMARY KEY,
                    geom geometry(Geometry, 4326),
                    sample_cnt int,
                    coverage_rate numeric,
                    avg_rsrp numeric,
                    avg_sinr numeric,
                    dt varchar(16)
                )""";
        jdbcTemplate.execute(String.format(gridDdl, "ott_temp.yunnan_5g_grid_detail_ztest_all"));
        jdbcTemplate.execute(String.format(gridDdl, "ott_temp.yunnan_4g_grid_detail_ztest_all"));
        jdbcTemplate.execute(String.format(gridDdl, "xmap.layer_mdt_grid_nokia_50_info_week"));

        truncateAllMockTables();
        seedMockData();
    }

    @AfterAll
    void tearDownMockDatabase() {
        // 只 drop 自己建的表，保留 schema（schema 可能被别人共用）
        for (String table : MOCK_TABLES) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void truncateAllMockTables() {
        for (String table : MOCK_TABLES) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
    }

    /** polygon 中心 (103.0, 25.0)；内部点以它为基准；外部点故意放到 polygon 之外 */
    private void seedMockData() {
        // 内部点
        String insideInsertCell = "INSERT INTO %s (geom) VALUES (ST_SetSRID(ST_MakePoint(?, ?), 4326))";
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update(String.format(insideInsertCell, "xmap.layer_wy_4g_cell_info_section_yn"),
                    102.9 + i * 0.05, 24.9);
        }
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update(String.format(insideInsertCell, "xmap.layer_wy_5g_cell_info_section_yn"),
                    103.0 + i * 0.02, 25.0);
        }
        jdbcTemplate.update(String.format(insideInsertCell, "xmap.layer_wy_2g_cell_info_section_yn"),
                103.0, 25.0);
        // 外部点：经度 104.0（polygon 上限是 103.5）
        jdbcTemplate.update(String.format(insideInsertCell, "xmap.layer_wy_4g_cell_info_section_yn"), 104.0, 25.0);
        jdbcTemplate.update(String.format(insideInsertCell, "xmap.layer_wy_5g_cell_info_section_yn"), 104.0, 25.0);

        // 楼宇（polygon 内 5 栋 + polygon 外 1 栋）
        String insideBuilding =
                "INSERT INTO xmap_deal.ott_yunnan_buildings (build_geom) VALUES (ST_SetSRID(ST_MakePoint(?, ?), 4326))";
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update(insideBuilding, 103.0 + i * 0.01, 25.0);
        }
        jdbcTemplate.update(insideBuilding, 104.0, 25.0);

        // 弱覆盖区域（OTT 5G、OTT 4G、MDT 4G 月）
        insertRegion("xmap_ott.layer_yunnan_5g_weak_coverage_region", 103.0, 25.0, 4, TEST_DT);
        insertRegion("xmap_ott.layer_yunnan_5g_weak_coverage_region", 103.1, 25.1, 2, TEST_DT);
        insertRegion("xmap_ott.layer_yunnan_5g_weak_coverage_region", 104.0, 25.0, 9, TEST_DT); // 外部 -> 不应计入

        insertRegion("xmap_ott.layer_yunnan_4g_weak_coverage_region", 103.0, 25.0, 5, TEST_DT);
        insertRegion("xmap_ott.layer_yunnan_4g_weak_coverage_region", 103.2, 25.2, 3, TEST_DT);
        insertRegion("xmap_ott.layer_yunnan_4g_weak_coverage_region", 103.3, 25.3, 1, TEST_DT);

        insertRegion("xmap.layer_mdt_grid_nokia_50_month_weakarea", 103.0, 25.0, 3, TEST_DT);
        insertRegion("xmap.layer_mdt_grid_nokia_50_month_weakarea", 103.1, 25.1, 1, TEST_DT);

        // 栅格数据（OTT 5G / OTT 4G / MDT 4G）
        // 内部：3 条正常 + 1 条弱覆盖；外部：1 条噪声
        insertGrid("ott_temp.yunnan_5g_grid_detail_ztest_all", 103.0, 25.0, 60, 98, -90, 10, TEST_DT);
        insertGrid("ott_temp.yunnan_5g_grid_detail_ztest_all", 103.1, 25.0, 80, 95, -92, 9, TEST_DT);
        insertGrid("ott_temp.yunnan_5g_grid_detail_ztest_all", 103.2, 25.0, 70, 96, -91, 8, TEST_DT);
        insertGrid("ott_temp.yunnan_5g_grid_detail_ztest_all", 103.3, 25.0, 80, 70, -98, 5, TEST_DT); // weak
        insertGrid("ott_temp.yunnan_5g_grid_detail_ztest_all", 104.0, 25.0, 60, 99, -88, 12, TEST_DT); // 外部

        insertGrid("ott_temp.yunnan_4g_grid_detail_ztest_all", 103.0, 25.0, 150, 98, -92, 10, TEST_DT);
        insertGrid("ott_temp.yunnan_4g_grid_detail_ztest_all", 103.1, 25.0, 200, 95, -93, 9, TEST_DT);
        insertGrid("ott_temp.yunnan_4g_grid_detail_ztest_all", 103.2, 25.0, 180, 96, -90, 8, TEST_DT);
        insertGrid("ott_temp.yunnan_4g_grid_detail_ztest_all", 103.3, 25.0, 200, 70, -102, 5, TEST_DT); // weak
        insertGrid("ott_temp.yunnan_4g_grid_detail_ztest_all", 104.0, 25.0, 150, 99, -88, 12, TEST_DT); // 外部

        insertGrid("xmap.layer_mdt_grid_nokia_50_info_week", 103.0, 25.0, 150, 97, -92, 10, TEST_DT);
        insertGrid("xmap.layer_mdt_grid_nokia_50_info_week", 103.1, 25.0, 180, 96, -93, 9, TEST_DT);
    }

    private void insertRegion(String table, double lon, double lat, int gridCount, String dt) {
        jdbcTemplate.update(
                "INSERT INTO " + table + " (geom, grid_count, dt) VALUES (ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)",
                lon, lat, gridCount, dt);
    }

    private void insertGrid(String table, double lon, double lat,
                            int sampleCnt, double coverageRate, double avgRsrp, double avgSinr, String dt) {
        jdbcTemplate.update(
                "INSERT INTO " + table
                        + " (geom, sample_cnt, coverage_rate, avg_rsrp, avg_sinr, dt) "
                        + "VALUES (ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?, ?, ?)",
                lon, lat, sampleCnt, coverageRate, avgRsrp, avgSinr, dt);
    }

    @Test
    void skillRegisteredInDatabaseWithExpectedAnchors() {
        List<SkillPrompt> prompts = skillDefinitionService.listEnabledSkillPrompts(AGENT_ID);
        Optional<SkillPrompt> coverageSkill = prompts.stream()
                .filter(prompt -> SKILL_NAME.equals(prompt.skillName()))
                .findFirst();
        assertTrue(coverageSkill.isPresent(), "skill.coverage.analysis not found for dev-agent");

        String template = coverageSkill.get().promptTemplate();
        assertAnchors(template,
                "db.schema.inspect",
                "artifact.markdown",
                "覆盖率",
                "弱覆盖",
                "扇区",
                "# 覆盖分析报告",
                "1. 区域扇区概况",
                "2. 弱覆盖区域占比",
                "3. 覆盖率总览",
                "4. 弱覆盖栅格分布",
                "5. 结论与建议",
                "{{geometry_json}}",
                "{{dt}}",
                "ST_Intersects",
                "ST_GeomFromGeoJSON",
                // 覆盖率指标源强化
                "coverage_type",
                "ott_temp.yunnan_5g_grid_detail_ztest_all",
                "数据暂缺",
                // 新增：白名单/禁查/固定 plan/违规自检
                "表白名单",
                "禁查清单",
                "information_schema",
                "public.*",
                "违规自检",
                "sector",
                "weak_area",
                "coverage",
                "weak_grid",
                "report"
        );
    }

    @Test
    void promptAssemblerInjectsCoverageSkillIntoAssembledContext() {
        RunRequest request = new RunRequest(
                "coverage-skill-run-" + UUID.randomUUID(),
                "coverage-skill-session-" + UUID.randomUUID(),
                AGENT_ID,
                "junit-user",
                "对昆明主城进行覆盖分析",
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        PromptContext context = promptAssembler.assemble(request);
        assertNotNull(context, "prompt context should not be null");
        String assembled = context.assembledPrompt();
        assertNotNull(assembled, "assembled prompt should not be null");
        assertTrue(assembled.contains("skills:"), "assembled prompt missing skills section header");
        assertTrue(assembled.contains(SKILL_NAME),
                "assembled prompt did not include " + SKILL_NAME + ". Prompt=\n" + assembled);
        assertTrue(assembled.contains("db.schema.inspect"),
                "assembled prompt missing db.schema.inspect guidance");
    }

    @Test
    void promptAssemblerInjectsSecondPrecisionSystemTime() {
        // 修复"生成时间字段被 LLM 写成模糊'2026年'"的历史 bug —— 报告里需要一个精确到秒的时间戳，
        // prompt 必须显式注入当前系统时间 + 告诉 LLM 用这个值。
        RunRequest request = new RunRequest(
                "time-check-run-" + UUID.randomUUID(),
                "time-check-session-" + UUID.randomUUID(),
                AGENT_ID,
                "junit-user",
                "什么时间？",
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        PromptContext context = promptAssembler.assemble(request);
        String assembled = context.assembledPrompt();

        assertTrue(assembled.contains("[系统时间]"),
                "assembled prompt 必须带 [系统时间] 标签，实际=\n" + assembled);

        // 精确到秒 → 匹配 YYYY-MM-DD HH:mm:ss 形态
        java.util.regex.Matcher tsMatcher =
                java.util.regex.Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\b")
                        .matcher(assembled);
        assertTrue(tsMatcher.find(),
                "assembled prompt 必须包含 YYYY-MM-DD HH:mm:ss 形态的时间戳，实际=\n" + assembled);

        // 解析出来的时间应跟当前系统时钟差不到 5 秒
        java.time.LocalDateTime stamped = java.time.LocalDateTime.parse(
                tsMatcher.group(1),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long driftSeconds = Math.abs(java.time.Duration.between(
                stamped, java.time.LocalDateTime.now()).getSeconds());
        assertTrue(driftSeconds < 10,
                "注入的时间戳应在当前时钟 ±10 秒内。stamped=" + stamped + ", drift=" + driftSeconds + "s");

        // 明确指引 LLM 使用这个时间，不要自己臆造
        assertTrue(assembled.contains("生成时间") && assembled.contains("必须使用这个时间戳"),
                "prompt 应显式提示 LLM 报告里的生成时间必须来自注入的时间戳: \n" + assembled);
    }

    /** 从 skill prompt 中抽出每个 ```sql ... ``` 代码块，逐条 statement 真实执行在 mock 表上 */
    @Test
    void skillSqlTemplatesExecuteOnMockPostgis() {
        String template = loadSkillTemplate();

        List<String> statements = extractSqlStatements(template);
        assertFalse(statements.isEmpty(), "skill prompt should contain at least one SQL code block");

        // 至少预期 10 条业务 statement（4 扇区/楼宇 + 3 弱覆盖区域 + 3 覆盖率）
        assertTrue(statements.size() >= 10,
                "expected >=10 SQL templates in skill prompt, got " + statements.size()
                        + ". Statements:\n" + String.join("\n---\n", statements));

        for (String stmt : statements) {
            String sql = stmt
                    .replace("{{geometry_json}}", TEST_GEOMETRY_JSON)
                    .replace("{{dt}}", TEST_DT);
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList(sql);
            } catch (RuntimeException error) {
                fail("SQL template failed to execute on mock postgis:\n" + sql
                        + "\nerror=" + error.getMessage());
                return;
            }
            assertFalse(rows.isEmpty(), "SQL template returned no row (aggregate SQL should always produce 1 row):\n" + sql);
        }
    }

    /** 覆盖率、弱覆盖等关键指标要算出"我们预期的"业务数字 */
    @Test
    void skillSqlTemplatesProduceExpectedBusinessNumbers() {
        String template = loadSkillTemplate();
        List<String> statements = extractSqlStatements(template);

        Map<String, List<Map<String, Object>>> resultsByTable = new LinkedHashMap<>();
        for (String stmt : statements) {
            String sql = stmt
                    .replace("{{geometry_json}}", TEST_GEOMETRY_JSON)
                    .replace("{{dt}}", TEST_DT);
            String tableName = matchTableFromStatement(sql);
            if (tableName == null) {
                continue;
            }
            resultsByTable.put(tableName, jdbcTemplate.queryForList(sql));
        }

        assertEquals(2L,
                numberValue(resultsByTable, "xmap.layer_wy_4g_cell_info_section_yn", "cell_cnt"),
                "4G 扇区 polygon 内应为 2 个");
        assertEquals(3L,
                numberValue(resultsByTable, "xmap.layer_wy_5g_cell_info_section_yn", "cell_cnt"),
                "5G 扇区 polygon 内应为 3 个");
        assertEquals(1L,
                numberValue(resultsByTable, "xmap.layer_wy_2g_cell_info_section_yn", "cell_cnt"),
                "2G 扇区 polygon 内应为 1 个");
        assertEquals(5L,
                numberValue(resultsByTable, "xmap_deal.ott_yunnan_buildings", "building_cnt"),
                "楼宇 polygon 内应为 5 栋");

        // OTT 5G 弱覆盖：polygon 内 2 个，grid_count>=3 的只有 1 个
        assertEquals(2L,
                numberValue(resultsByTable, "xmap_ott.layer_yunnan_5g_weak_coverage_region", "total_region"));
        assertEquals(1L,
                numberValue(resultsByTable, "xmap_ott.layer_yunnan_5g_weak_coverage_region", "major_region"));

        // OTT 4G：3 个，grid_count>=3 的 2 个
        assertEquals(3L,
                numberValue(resultsByTable, "xmap_ott.layer_yunnan_4g_weak_coverage_region", "total_region"));
        assertEquals(2L,
                numberValue(resultsByTable, "xmap_ott.layer_yunnan_4g_weak_coverage_region", "major_region"));

        // MDT 4G 月：2 个，grid_count>=3 的 1 个
        assertEquals(2L,
                numberValue(resultsByTable, "xmap.layer_mdt_grid_nokia_50_month_weakarea", "total_region"));
        assertEquals(1L,
                numberValue(resultsByTable, "xmap.layer_mdt_grid_nokia_50_month_weakarea", "major_region"));

        // 5G 栅格：内部 4 条，其中 1 条弱（sample_cnt=80, avg_rsrp=-98，符合 sample_cnt>=50 && avg_rsrp<-96）
        assertEquals(4L, numberValue(resultsByTable,
                "ott_temp.yunnan_5g_grid_detail_ztest_all", "grid_cnt"));
        assertEquals(1L, numberValue(resultsByTable,
                "ott_temp.yunnan_5g_grid_detail_ztest_all", "weak_grid_cnt"));

        // 4G 栅格同理
        assertEquals(4L, numberValue(resultsByTable,
                "ott_temp.yunnan_4g_grid_detail_ztest_all", "grid_cnt"));
        assertEquals(1L, numberValue(resultsByTable,
                "ott_temp.yunnan_4g_grid_detail_ztest_all", "weak_grid_cnt"));

        // 覆盖率应严格介于 [0, 100]（mock 中填的是百分数口径）
        Number coverageRate5g = (Number) resultsByTable
                .get("ott_temp.yunnan_5g_grid_detail_ztest_all").get(0).get("coverage_rate");
        assertNotNull(coverageRate5g, "OTT 5G 覆盖率不应为 null");
        double coverage = coverageRate5g.doubleValue();
        assertTrue(coverage > 0 && coverage <= 100,
                "OTT 5G 加权覆盖率应在 (0, 100] 区间，实际=" + coverage);
    }

    @Test
    void artifactMarkdownToolPersistsCoverageReport() throws Exception {
        String reportMarkdown = """
                # 覆盖分析报告
                > 区域：昆明主城 ；数据时间：2026-03

                ## 1. 区域扇区概况
                - 2G 扇区：1 个
                - 4G 扇区：2 个
                - 5G 扇区：3 个
                - 楼宇：5 栋

                ## 2. 弱覆盖区域占比
                - OTT 5G 弱覆盖区域：2 个，其中 grid_count ≥ 3 的 1 个（50.0%）
                - OTT 4G 弱覆盖区域：3 个，其中 grid_count ≥ 3 的 2 个（66.7%）
                - MDT 4G 弱覆盖区域：2 个，其中 grid_count ≥ 3 的 1 个（50.0%）

                ## 3. 覆盖率总览
                - OTT 5G 覆盖率：89.3% ，平均 RSRP：-93 dBm
                - OTT 4G 覆盖率：92.0% ，平均 RSRP：-94 dBm
                - MDT 4G 覆盖率：96.5% ，平均 RSRP：-92 dBm

                ## 4. 弱覆盖栅格分布
                - 有效栅格总数：8 个
                - 弱覆盖栅格数：2 个
                - 弱覆盖占比：25.0%

                ## 5. 结论与建议
                - 4G 存在明显弱覆盖区域（3 处 OTT + 2 处 MDT），建议现场巡检
                - 5G 弱覆盖栅格占比 25%，需优化
                - 楼宇密度 5 栋，建议补覆盖室分
                """;

        String payload = objectMapper.writeValueAsString(Map.of(
                "name", "coverage-analysis-report.md",
                "content", reportMarkdown
        ));

        ToolInvocation invocation = new ToolInvocation(
                AGENT_ID,
                "coverage-skill-run-" + UUID.randomUUID(),
                "coverage-skill-session-" + UUID.randomUUID(),
                "junit-user",
                "artifact.markdown",
                payload
        );

        ToolResult result = artifactMarkdownTool.execute(invocation);
        if (!result.ok()) {
            fail("artifact.markdown failed: " + result.error());
        }

        JsonNode data = objectMapper.readTree(result.dataJson());
        assertEquals("markdown", data.path("displayType").asText());
        assertFalse(data.path("artifactId").asText().isBlank(), "artifactId must be returned");
        assertTrue(data.path("name").asText().endsWith(".md"), "artifact name must carry .md extension");

        String storedMarkdown = data.path("markdown").asText();
        assertAnchors(storedMarkdown,
                "# 覆盖分析报告",
                "## 1. 区域扇区概况",
                "## 2. 弱覆盖区域占比",
                "## 3. 覆盖率总览",
                "## 4. 弱覆盖栅格分布",
                "## 5. 结论与建议",
                "覆盖率",
                "平均 RSRP",
                "dBm"
        );

        JsonNode artifacts = objectMapper.readTree(result.artifactsJson());
        assertTrue(artifacts.isArray() && !artifacts.isEmpty(), "artifactsJson should contain saved artifact");
        assertEquals("text/markdown; charset=UTF-8", artifacts.get(0).path("contentType").asText());
    }

    private String loadSkillTemplate() {
        return skillDefinitionService.listEnabledSkillPrompts(AGENT_ID).stream()
                .filter(prompt -> SKILL_NAME.equals(prompt.skillName()))
                .map(SkillPrompt::promptTemplate)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("skill not found"));
    }

    private List<String> extractSqlStatements(String template) {
        List<String> statements = new ArrayList<>();
        Matcher matcher = SQL_BLOCK_PATTERN.matcher(template);
        while (matcher.find()) {
            String block = matcher.group(1);
            for (String raw : block.split(";")) {
                String cleaned = stripCommentLines(raw).trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                statements.add(cleaned);
            }
        }
        return statements;
    }

    private String stripCommentLines(String raw) {
        StringBuilder buffer = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            buffer.append(line).append('\n');
        }
        return buffer.toString();
    }

    private String matchTableFromStatement(String sql) {
        for (String table : MOCK_TABLES) {
            if (sql.contains(table)) {
                return table;
            }
        }
        return null;
    }

    private long numberValue(Map<String, List<Map<String, Object>>> results, String table, String column) {
        List<Map<String, Object>> rows = results.get(table);
        assertNotNull(rows, "no result for table: " + table);
        assertFalse(rows.isEmpty(), "empty result for table: " + table);
        Object value = rows.get(0).get(column);
        assertNotNull(value, "null value for " + table + "." + column);
        return ((Number) value).longValue();
    }

    private void assertAnchors(String content, String... anchors) {
        assertNotNull(content, "content must not be null");
        for (String anchor : anchors) {
            assertTrue(content.contains(anchor),
                    "expected content to contain anchor '" + anchor + "' but it did not. Content length=" + content.length());
        }
    }
}
