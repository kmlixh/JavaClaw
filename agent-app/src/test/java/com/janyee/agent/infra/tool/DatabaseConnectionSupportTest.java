package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.runtime.datasource.DatasourceResource;
import com.janyee.agent.runtime.datasource.DatasourceResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DatabaseConnectionSupport 的凭证复用路径：
 * 1) 带完整 jdbcUrl + username + password 能成功连接并写入缓存
 * 2) 后续只带 jdbcUrl（省略 username/password）能从缓存自动补齐并成功连接
 * 3) 缓存未命中且只带 jdbcUrl 时抛出带指引的错误
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseConnectionSupportTest {

    @Autowired
    private DataSource dataSource;

    private ObjectMapper objectMapper;

    @BeforeEach
    void resetCache() {
        this.objectMapper = new ObjectMapper();
        // 清空缓存，避免前面用例遗留状态影响当前断言
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        DatabaseConnectionSupport.credentialCache().invalidate(url);
    }

    @Test
    void firstCallWithFullCredentialsSeedsCacheAndConnects() throws Exception {
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        String args = objectMapper.writeValueAsString(Map.of(
                "jdbcUrl", url,
                "username", "postgres",
                "password", "JL0Is1KqGuQMayeF"
        ));

        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args))) {
            assertNotNull(target.connection());
            assertEquals("explicit-jdbc-url", target.mode());
            assertEquals(url, target.jdbcUrl());
        }
        // 凭证应进入缓存
        assertEquals("postgres",
                DatabaseConnectionSupport.credentialCache().get(url).orElseThrow().username());
    }

    @Test
    void secondCallOmittingCredentialsIsFilledFromCache() throws Exception {
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        // 先建一次完整连接，种入缓存
        try (DatabaseConnectionSupport.ConnectionTarget ignored =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(
                             objectMapper.writeValueAsString(Map.of(
                                     "jdbcUrl", url,
                                     "username", "postgres",
                                     "password", "JL0Is1KqGuQMayeF"))))) {
            assertNotNull(ignored.connection());
        }

        // 第二次只带 jdbcUrl
        String args = objectMapper.writeValueAsString(Map.of("jdbcUrl", url));
        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args))) {
            assertNotNull(target.connection(), "cached credentials must satisfy the second call");
            assertEquals("explicit-jdbc-url", target.mode());
            assertEquals(url, target.jdbcUrl());
        }
    }

    @Test
    void jdbcUrlMissingJdbcPrefixIsAutoNormalized() {
        assertEquals(
                "jdbc:postgresql://10.174.238.4:5432/gis",
                DatabaseConnectionSupport.normalizeJdbcUrl("postgresql://10.174.238.4:5432/gis"));
        assertEquals(
                "jdbc:mysql://host:3306/db",
                DatabaseConnectionSupport.normalizeJdbcUrl("mysql://host:3306/db"));
        assertEquals(
                "jdbc:sqlserver://host:1433;databaseName=X",
                DatabaseConnectionSupport.normalizeJdbcUrl("sqlserver://host:1433;databaseName=X"));
        // already prefixed → unchanged
        assertEquals(
                "jdbc:postgresql://host/db",
                DatabaseConnectionSupport.normalizeJdbcUrl("jdbc:postgresql://host/db"));
        // unknown scheme → unchanged (let DriverManager surface the real error)
        assertEquals(
                "weird://whatever",
                DatabaseConnectionSupport.normalizeJdbcUrl("weird://whatever"));
        assertEquals("", DatabaseConnectionSupport.normalizeJdbcUrl(null));
        assertEquals("", DatabaseConnectionSupport.normalizeJdbcUrl("   "));
    }

    @Test
    void hintFieldOnlyArgumentsFallBackInsteadOfThrowing() throws Exception {
        // LLM writes `{"sql":"...","database":"gis"}` — no host/port/dbType/jdbcUrl.
        // Previously this tripped inferDbType(0) and crashed; now it must fall back.
        String args = objectMapper.writeValueAsString(Map.of("database", "gis"));
        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args))) {
            assertNotNull(target.connection(), "hint-only args must fall back to the default datasource");
            assertEquals("fallback-datasource", target.mode());
        }
    }

    @Test
    void schemaAloneAlsoFallsBack() throws Exception {
        String args = objectMapper.writeValueAsString(Map.of("schema", "xmap_ott"));
        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args))) {
            assertEquals("fallback-datasource", target.mode());
        }
    }

    @Test
    void hostWithoutUsernameRaisesHelpfulError() throws Exception {
        // Real remote intent (host present) but credentials missing — must throw with guidance.
        String args = objectMapper.writeValueAsString(Map.of(
                "host", "10.1.2.3",
                "dbType", "postgresql",
                "database", "gis"
        ));
        IllegalArgumentException err = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args)));
        org.junit.jupiter.api.Assertions.assertTrue(
                err.getMessage().contains("username") || err.getMessage().contains("incomplete"),
                "error should guide the caller to pass username/credentials. actual=" + err.getMessage());
    }

    @Test
    void dbTypeMissingAndUnrecognizedPortRaisesHelpfulError() throws Exception {
        String args = objectMapper.writeValueAsString(Map.of(
                "host", "10.1.2.3",
                "port", 12345,
                "database", "gis",
                "username", "u",
                "password", "p"
        ));
        IllegalArgumentException err = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args)));
        String msg = err.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                msg.contains("dbType") && msg.contains("jdbcUrl"),
                "error should suggest supplying dbType or jdbcUrl. actual=" + msg);
    }

    @Test
    void registeredResourceOverridesArgsCredentials() throws Exception {
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        DatabaseConnectionSupport.credentialCache().invalidate(url);

        // LLM 错误地把凭证写进 args（历史 bug）。若 resource service 已登记此 jdbcUrl，
        // DatabaseConnectionSupport 必须忽略 args 里的 username/password，改用资源里真实的凭证。
        // 如果这条路径失守，LLM 的乱填可能直接刷到数据库。
        String args = objectMapper.writeValueAsString(Map.of(
                "jdbcUrl", url,
                "username", "llm-guessed-wrong-user",
                "password", "llm-guessed-wrong-password"
        ));

        DatasourceResourceService resourceService = new DatasourceResourceService() {
            @Override
            public Optional<DatasourceResource> findByJdbcUrl(String jdbcUrl) {
                if (url.equals(jdbcUrl)) {
                    return Optional.of(new DatasourceResource(
                            "ds-java-claw",
                            "java_claw (test)",
                            url,
                            "postgres",
                            "JL0Is1KqGuQMayeF",
                            "postgresql"
                    ));
                }
                return Optional.empty();
            }

            @Override
            public Optional<DatasourceResource> findById(String id) {
                return Optional.empty();
            }

            @Override
            public Optional<DatasourceResource> findDefault() {
                return Optional.empty();
            }
        };

        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args), resourceService)) {
            assertNotNull(target.connection(),
                    "resource-injected credentials must establish the connection even when args contain garbage");
            assertEquals("explicit-jdbc-url", target.mode());
            assertEquals(url, target.jdbcUrl());
        }

        // 注册凭证应写入缓存（后续省略凭证也能命中）
        assertEquals("postgres",
                DatabaseConnectionSupport.credentialCache().get(url).orElseThrow().username());
    }

    @Test
    void unregisteredJdbcUrlFallsBackToArgsCredentials() throws Exception {
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        DatabaseConnectionSupport.credentialCache().invalidate(url);

        DatasourceResourceService noneRegistered = new DatasourceResourceService() {
            @Override
            public Optional<DatasourceResource> findByJdbcUrl(String jdbcUrl) {
                return Optional.empty();
            }

            @Override
            public Optional<DatasourceResource> findById(String id) {
                return Optional.empty();
            }

            @Override
            public Optional<DatasourceResource> findDefault() {
                return Optional.empty();
            }
        };

        String args = objectMapper.writeValueAsString(Map.of(
                "jdbcUrl", url,
                "username", "postgres",
                "password", "JL0Is1KqGuQMayeF"
        ));

        try (DatabaseConnectionSupport.ConnectionTarget target =
                     DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args), noneRegistered)) {
            assertNotNull(target.connection(), "absent resource ⇒ args creds remain the authoritative source");
            assertEquals("explicit-jdbc-url", target.mode());
        }
        assertTrue(DatabaseConnectionSupport.credentialCache().get(url).isPresent(),
                "args creds should still populate the cache when resource service is wired but doesn't register this url");
    }

    @Test
    void missingCredentialsWithoutCacheRaisesHelpfulError() throws Exception {
        String url = "jdbc:postgresql://10.173.108.120:5433/java_claw";
        DatabaseConnectionSupport.credentialCache().invalidate(url);

        String args = objectMapper.writeValueAsString(Map.of("jdbcUrl", url));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnectionSupport.openConnection(dataSource, objectMapper.readTree(args)));
        String message = error.getMessage();
        assertNotNull(message);
        // 错误消息必须告诉 LLM 需要重传凭证
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("username"),
                "error should instruct caller to retry with username. actual=" + message);
        org.junit.jupiter.api.Assertions.assertTrue(message.contains(url),
                "error should echo the jdbcUrl. actual=" + message);
    }
}
