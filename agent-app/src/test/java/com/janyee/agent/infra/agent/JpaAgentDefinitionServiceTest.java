package com.janyee.agent.infra.agent;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.domain.AgentDefinition;
import com.janyee.agent.infra.persistence.entity.AgentDefinitionEntity;
import com.janyee.agent.infra.persistence.repository.AgentDefinitionRepository;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.AgentDefinitionCommand;
import com.janyee.agent.runtime.admin.AgentDefinitionView;
import com.janyee.agent.runtime.agent.AgentDefinitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan A Phase 2 回归：agent 定义从数据库读而不是从 workspaces/&lt;agent&gt;/*.md 文件读，并且
 * admin CRUD 可编辑 displayName / systemPrompt / agentMarkdown / memoryMarkdown。
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
class JpaAgentDefinitionServiceTest {

    @Autowired
    private AgentDefinitionService agentDefinitionService;

    @Autowired
    private AdminCatalogService adminCatalogService;

    @Autowired
    private AgentDefinitionRepository repository;

    private final java.util.List<String> seededIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : seededIds) {
            repository.findById(id).ifPresent(repository::delete);
        }
        seededIds.clear();
    }

    @Test
    void seededAgentsAreReturnedByGetAgent() {
        // V16 迁移已经把 dev-agent / ops-agent 种进表，这条测试只验证读通路。
        AgentDefinition dev = agentDefinitionService.getAgent("dev-agent");
        assertNotNull(dev);
        assertEquals("dev-agent", dev.id());
        assertFalse(dev.systemPrompt().isBlank(),
                "系统提示词应从数据库 agent_definition.system_prompt 读到");
    }

    @Test
    void adminCanCreateEditDeleteAgent() {
        String newId = "junit-agent-" + UUID.randomUUID().toString().substring(0, 6);
        seededIds.add(newId);

        // create
        AgentDefinitionView created = adminCatalogService.saveAgentDefinition(new AgentDefinitionCommand(
                newId, "Junit Agent", "test-only agent",
                "You are a junit agent.", "# Junit\nUsed only in tests.", "Remember: tests.",
                true, null, null, null, null));
        assertEquals(newId, created.agentId());
        assertEquals("Junit Agent", created.displayName());

        // read via service
        AgentDefinition fetched = agentDefinitionService.getAgent(newId);
        assertEquals("Junit Agent", fetched.displayName());
        assertTrue(fetched.systemPrompt().contains("junit agent"),
                "新建 agent 的 systemPrompt 必须从 DB 读而不是 fallback: " + fetched.systemPrompt());

        // edit
        AgentDefinitionView edited = adminCatalogService.saveAgentDefinition(new AgentDefinitionCommand(
                newId, "Junit Agent v2", "edited",
                "You are a junit agent v2.", "# Junit v2", "Remember v2.",
                true, null, null, null, null));
        assertEquals("Junit Agent v2", edited.displayName());
        assertNotNull(edited.updatedAt(),
                "edit 后 updated_at 必须刷新");

        // list contains it
        List<AgentDefinitionView> list = adminCatalogService.listAgentDefinitions();
        assertTrue(list.stream().anyMatch(v -> newId.equals(v.agentId())),
                "admin list 应包含新创建的 agent");

        // delete
        adminCatalogService.deleteAgentDefinition(newId);
        assertFalse(repository.findById(newId).isPresent());
    }

    @Test
    void disabledAgentFallsBackToWorkspaceFilesIfPresent() {
        // disabled 的 agent 不应直接由 getAgent 返回 DB 条目 —— 走 fallback 路径读文件
        String newId = "disabled-junit-" + UUID.randomUUID().toString().substring(0, 6);
        seededIds.add(newId);
        adminCatalogService.saveAgentDefinition(new AgentDefinitionCommand(
                newId, "Disabled", "test", "DISABLED PROMPT", "# D", "", false,
                null, null, null, null));
        AgentDefinition def = agentDefinitionService.getAgent(newId);
        // DB disabled → fallback 从文件读（新 agent 没文件 → 默认系统提示）
        assertFalse(def.systemPrompt().contains("DISABLED PROMPT"),
                "disabled 的 agent 不应直接返回 DB 里的 systemPrompt，应走 fallback 或默认: "
                        + def.systemPrompt());
    }
}
