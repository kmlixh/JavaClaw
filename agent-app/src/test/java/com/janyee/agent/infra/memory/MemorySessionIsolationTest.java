package com.janyee.agent.infra.memory;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import com.janyee.agent.infra.persistence.repository.MemoryNoteRepository;
import com.janyee.agent.memory.MemoryItem;
import com.janyee.agent.memory.MemoryQuery;
import com.janyee.agent.memory.MemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 再现并锁定"跨会话 memory 泄露"bug：
 *
 * <p>用户观察：agent=dev-agent，session A 跑完一次"盘龙区覆盖分析"后把扇区/覆盖率数字存进
 * memory_note（source=run_summary）。之后一个全新的 session B 只问同一个问题、只跑了 1-2 次
 * db.query，生成的报告里却出现了 session A 的完整数字 —— LLM 把 memory 里的旧数字当成自己查
 * 到的抄了进去。</p>
 *
 * <p>修复后：</p>
 * <ol>
 *   <li>{@code MemoryQuery} 携带 sessionId；</li>
 *   <li>{@code retrieve} 只返回 (本 session 的 note) ∪ (source=pinned 的显式备注)；</li>
 *   <li>{@code run_summary} 类别的 note 出现在 session A，session B 的 retrieve 绝不会命中它。</li>
 * </ol>
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
class MemorySessionIsolationTest {

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private MemoryNoteRepository memoryNoteRepository;

    private final java.util.List<Long> seededIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : seededIds) {
            memoryNoteRepository.findById(id).ifPresent(memoryNoteRepository::delete);
        }
        seededIds.clear();
    }

    @Test
    void runSummaryFromAnotherSessionMustNotLeakIntoCurrentSession() {
        String agentId = "dev-agent";
        String sessionA = "sess-A-" + UUID.randomUUID();
        String sessionB = "sess-B-" + UUID.randomUUID();

        // Session A 跑完"盘龙区覆盖分析"后把数字写入 memory —— 这正是线上 bug 的来源。
        seededIds.add(saveNote(agentId, sessionA,
                "User: 给出盘龙区的覆盖分析综合报告\n"
                        + "Assistant: 2G 58202 + 4G 309811 + 5G 169249 扇区；OTT 5G 覆盖率 95.93%",
                "run_summary"));

        // Session B 用完全一样的问题 retrieve ——
        // 修复前会命中 session A 的数字，修复后应返回空。
        List<MemoryItem> leaked = memoryService.retrieve(
                new MemoryQuery(agentId, sessionB, "盘龙区 覆盖 分析"));

        for (MemoryItem item : leaked) {
            assertFalse(item.content().contains("58202") || item.content().contains("309811"),
                    "Session B 的 memory retrieve 不应看到 Session A 的 run_summary 数字。leaked=" + item.content());
        }
    }

    @Test
    void agentScopeNotesAreStillSharedAcrossSessions() {
        // Plan A Phase 1 之后，"显式长期备注"由 scope='agent' 表达（V14 把历史 pinned 也迁成 agent scope）。
        // 用户主动通过 admin UI 标注的 note 进入 agent scope → 跨 session 可见。
        String agentId = "dev-agent";
        String sessionA = "sess-A-" + UUID.randomUUID();
        String sessionB = "sess-B-" + UUID.randomUUID();
        seededIds.add(saveNoteWithScope(agentId, sessionA,
                "盘龙区覆盖分析的 OTT 口径只能按 city 过滤，无法细化到 county",
                "pinned", "agent"));

        List<MemoryItem> items = memoryService.retrieve(
                new MemoryQuery(agentId, sessionB, "盘龙区 覆盖"));

        assertTrue(items.stream().anyMatch(i -> i.content().contains("无法细化到 county")),
                "agent-scope note 应可被其他 session 检索到: " + items);
    }

    @Test
    void withinSameSessionRunSummaryRemainsVisible() {
        // 同 session 内 run_summary 仍然要能被引用（连续对话的上下文）。
        String agentId = "dev-agent";
        String sessionId = "sess-" + UUID.randomUUID();
        seededIds.add(saveNote(agentId, sessionId,
                "User: 给出盘龙区覆盖分析\nAssistant: 4G 扇区总数 309811",
                "run_summary"));

        List<MemoryItem> items = memoryService.retrieve(
                new MemoryQuery(agentId, sessionId, "盘龙区 扇区"));
        assertTrue(items.stream().anyMatch(i -> i.content().contains("309811")),
                "同一 session 内的 run_summary 应仍然可见: " + items);
    }

    private Long saveNote(String agentId, String sessionId, String content, String source) {
        return saveNoteWithScope(agentId, sessionId, content, source, "session");
    }

    private Long saveNoteWithScope(String agentId, String sessionId, String content, String source, String scope) {
        MemoryNoteEntity e = new MemoryNoteEntity();
        e.setAgentId(agentId);
        e.setSessionId(sessionId);
        e.setRunId("run-" + UUID.randomUUID());
        e.setContent(content);
        e.setSource(source);
        e.setScope(scope);
        return memoryNoteRepository.save(e).getId();
    }
}
