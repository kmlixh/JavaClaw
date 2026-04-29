package com.janyee.agent.infra.admin;

import com.janyee.agent.app.AgentApplication;
import com.janyee.agent.infra.persistence.entity.SkillAgentBindingEntity;
import com.janyee.agent.infra.persistence.repository.SkillAgentBindingRepository;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 V19 之后 skill↔agent 的 M:N 关系端到端可用:
 *   1) 保存一个 skill 绑定多个 agent → binding 表出现对应 row + view.agentIds 返回完整集合
 *   2) 重复保存修改绑定集合 → binding 被重置(先删后插),没有残留
 *   3) listSkillDefinitions(agentId) 现在通过 binding 查,能找到"这个 skill 绑给了我"
 *   4) deleteSkillDefinition → binding 连带清理
 */
@SpringBootTest(classes = AgentApplication.class)
@ActiveProfiles("postgres")
class SkillAgentManyToManyTest {

    @Autowired
    private AdminCatalogService adminCatalogService;

    @Autowired
    private SkillAgentBindingRepository bindingRepository;

    private String createdSkillId;

    @AfterEach
    void cleanup() {
        if (createdSkillId != null) {
            try {
                adminCatalogService.deleteSkillDefinition(createdSkillId);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void saveSkillWithMultipleAgentIdsPersistsBindings() {
        String skillName = "skill.m2m.test." + UUID.randomUUID();
        SkillDefinitionCommand cmd = new SkillDefinitionCommand(
                null,
                null,
                List.of("dev-agent", "ops-agent"),
                skillName,
                "multi-agent skill test",
                "prompt body",
                "{}",
                "[]",
                true,
                null, null, null, null
        );
        SkillDefinitionView saved = adminCatalogService.saveSkillDefinition(cmd);
        createdSkillId = saved.id();
        assertNotNull(saved.id());

        Set<String> viewIds = Set.copyOf(saved.agentIds());
        assertEquals(Set.of("dev-agent", "ops-agent"), viewIds,
                "view 里的 agentIds 必须反映 binding 表里的完整集合");

        Set<String> persistedIds = bindingRepository.findBySkillId(saved.id()).stream()
                .map(SkillAgentBindingEntity::getAgentId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("dev-agent", "ops-agent"), persistedIds,
                "binding 表应有两行:(skill, dev-agent) 和 (skill, ops-agent)");
    }

    @Test
    void resavingSkillRewritesBindingSet() {
        String skillName = "skill.m2m.rewrite." + UUID.randomUUID();
        SkillDefinitionView first = adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                null, null, List.of("dev-agent", "ops-agent"),
                skillName, "initial", "prompt", "{}", "[]", true,
                null, null, null, null
        ));
        createdSkillId = first.id();
        assertEquals(Set.of("dev-agent", "ops-agent"), Set.copyOf(first.agentIds()));

        // 第二次保存只挂 dev-agent,ops-agent 应被移除
        SkillDefinitionView second = adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                first.id(), null, List.of("dev-agent"),
                skillName, "updated", "prompt", "{}", "[]", true,
                null, null, null, null
        ));
        assertEquals(Set.of("dev-agent"), Set.copyOf(second.agentIds()),
                "绑定集合缩减时 ops-agent 的 binding 必须被删掉,不是累加");

        Set<String> persistedIds = bindingRepository.findBySkillId(second.id()).stream()
                .map(SkillAgentBindingEntity::getAgentId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("dev-agent"), persistedIds);
    }

    @Test
    void listByAgentIdReturnsSkillsViaBinding() {
        String skillName = "skill.m2m.list." + UUID.randomUUID();
        SkillDefinitionView saved = adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                null, null, List.of("ops-agent"),
                skillName, "ops-only", "prompt", "{}", "[]", true,
                null, null, null, null
        ));
        createdSkillId = saved.id();

        List<SkillDefinitionView> opsSkills = adminCatalogService.listSkillDefinitions("ops-agent");
        assertTrue(opsSkills.stream().anyMatch(v -> skillName.equals(v.skillName())),
                "ops-agent 视角必须能看到新绑的 skill");

        List<SkillDefinitionView> devSkills = adminCatalogService.listSkillDefinitions("dev-agent");
        assertFalse(devSkills.stream().anyMatch(v -> skillName.equals(v.skillName())),
                "dev-agent 视角不应看到只绑给 ops-agent 的 skill");
    }

    @Test
    void deleteSkillClearsBindings() {
        String skillName = "skill.m2m.delete." + UUID.randomUUID();
        SkillDefinitionView saved = adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                null, null, List.of("dev-agent", "ops-agent"),
                skillName, "to delete", "prompt", "{}", "[]", true,
                null, null, null, null
        ));
        String skillId = saved.id();
        assertEquals(2, bindingRepository.findBySkillId(skillId).size());

        adminCatalogService.deleteSkillDefinition(skillId);
        createdSkillId = null;

        assertTrue(bindingRepository.findBySkillId(skillId).isEmpty(),
                "删除 skill 后 binding 必须跟着清空");
    }

    @Test
    void legacyAgentIdFallbackWorksWhenAgentIdsEmpty() {
        // 老 client 可能只发 agentId,不发 agentIds —— 应退化成单元素绑定
        String skillName = "skill.m2m.legacy." + UUID.randomUUID();
        SkillDefinitionView saved = adminCatalogService.saveSkillDefinition(new SkillDefinitionCommand(
                null, "dev-agent", null,
                skillName, "legacy client", "prompt", "{}", "[]", true,
                null, null, null, null
        ));
        createdSkillId = saved.id();
        assertEquals(List.of("dev-agent"), saved.agentIds(),
                "agentIds 缺失时用 legacy agentId 补位,生成一条 binding");
    }
}
