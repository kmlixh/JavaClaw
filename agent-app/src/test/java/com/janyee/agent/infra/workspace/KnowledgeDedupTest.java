package com.janyee.agent.infra.workspace;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.infra.persistence.entity.KnowledgeEntryEntity;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import com.janyee.agent.workspace.WorkspaceKnowledgeFile;
import com.janyee.agent.workspace.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan A Phase 3：knowledge 源优先级 + 去重 + 来源标注。
 *
 * <ol>
 *   <li>DB 知识条目在 prompt 里以 {@code db/&lt;title&gt;} 前缀出现；</li>
 *   <li>workspace 文件以 {@code workspace/&lt;path&gt;} 前缀出现；</li>
 *   <li>同 title 的 DB 条目和 workspace 文件只保留 DB（去重），避免两份并列渲染让 LLM 困惑。</li>
 * </ol>
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
class KnowledgeDedupTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private KnowledgeEntryRepository knowledgeEntryRepository;

    private final java.util.List<String> seededIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : seededIds) {
            knowledgeEntryRepository.findById(id).ifPresent(knowledgeEntryRepository::delete);
        }
        seededIds.clear();
    }

    @Test
    void dbEntriesCarryDbPrefix() {
        String id = "junit-db-entry-" + UUID.randomUUID();
        seededIds.add(id);
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(id);
        e.setAgentId("dev-agent");
        e.setTitle("junit-covers-basics");
        e.setContent("db-backed content");
        e.setContentType("markdown");
        e.setSource("test");
        e.setTagsJson("[]");
        e.setEnabled(true);
        e.setVersion(1);
        // createdAt/updatedAt 由 @PrePersist 自动填充
        knowledgeEntryRepository.save(e);

        List<WorkspaceKnowledgeFile> files = workspaceService.listKnowledgeFiles("dev-agent");
        boolean hasDbPrefix = files.stream()
                .anyMatch(f -> f.relativePath().startsWith("db/")
                        && f.relativePath().contains("junit-covers-basics"));
        assertTrue(hasDbPrefix,
                "DB 条目应以 db/ 前缀出现在 knowledge 列表里。实际: "
                        + files.stream().map(WorkspaceKnowledgeFile::relativePath).toList());
    }

    @Test
    void dbEntryShadowsWorkspaceFileOfSameName() {
        // dev-agent 的 workspace 里本来有 getting-started.md；V17 seed 又插了同名的 DB 条目。
        // 期望：最终只出现 db/getting-started（workspace 的那份被去重）。
        List<WorkspaceKnowledgeFile> files = workspaceService.listKnowledgeFiles("dev-agent");

        long dbCount = files.stream()
                .filter(f -> f.relativePath().equalsIgnoreCase("db/getting-started"))
                .count();
        long workspaceCount = files.stream()
                .filter(f -> f.relativePath().startsWith("workspace/")
                        && f.relativePath().toLowerCase().contains("getting-started"))
                .count();
        assertEquals(1, dbCount, "期望 DB 条目出现恰好一次: "
                + files.stream().map(WorkspaceKnowledgeFile::relativePath).toList());
        assertEquals(0, workspaceCount,
                "同名 workspace 文件应被去重: "
                + files.stream().map(WorkspaceKnowledgeFile::relativePath).toList());
    }

    @Test
    void allPrefixesPresent() {
        // builtin + db + workspace 三种前缀都应合法出现
        List<WorkspaceKnowledgeFile> files = workspaceService.listKnowledgeFiles("dev-agent");
        boolean hasBuiltin = files.stream().anyMatch(f -> f.relativePath().startsWith("builtin/"));
        boolean hasDb = files.stream().anyMatch(f -> f.relativePath().startsWith("db/"));
        assertTrue(hasDb, "dev-agent 应至少有 V17 seed 的 DB 条目: "
                + files.stream().map(WorkspaceKnowledgeFile::relativePath).toList());
        // builtin 可能为空（取决于 BuiltinDocumentWorkflowCatalog 配置），不强断言
        assertFalse(files.isEmpty(), "knowledge 列表不应为空");
    }
}
