package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinitionEntity, String> {

    /**
     * V19+ 等价物:{@code findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc}。通过
     * {@code skill_agent_binding} 桥表解析 M:N 关系。只返回 enabled=true 的技能。
     */
    @Query("SELECT s FROM SkillDefinitionEntity s "
            + "JOIN SkillAgentBindingEntity b ON b.skillId = s.id "
            + "WHERE b.agentId = :agentId AND s.enabled = true "
            + "ORDER BY s.updatedAt DESC")
    List<SkillDefinitionEntity> findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(@Param("agentId") String agentId);

    /** V19+ 等价物:{@code findByAgentIdOrderByUpdatedAtDesc}。包含 disabled 条目,供 admin 看全貌。 */
    @Query("SELECT s FROM SkillDefinitionEntity s "
            + "JOIN SkillAgentBindingEntity b ON b.skillId = s.id "
            + "WHERE b.agentId = :agentId "
            + "ORDER BY s.updatedAt DESC")
    List<SkillDefinitionEntity> findByAgentIdOrderByUpdatedAtDesc(@Param("agentId") String agentId);

    /** V19+ 等价物:{@code findByAgentIdAndSkillName}。用于 preset 服务按名查改。 */
    @Query("SELECT s FROM SkillDefinitionEntity s "
            + "JOIN SkillAgentBindingEntity b ON b.skillId = s.id "
            + "WHERE b.agentId = :agentId AND s.skillName = :skillName")
    Optional<SkillDefinitionEntity> findByAgentIdAndSkillName(
            @Param("agentId") String agentId,
            @Param("skillName") String skillName);

    /** 按 skill_name 全局查找(V19 起 skill_name 全局唯一)。 */
    Optional<SkillDefinitionEntity> findBySkillName(String skillName);

    /**
     * All skills with non-empty trigger_keywords, regardless of agent. Used by the
     * agent-mismatch early-warning check so we can tell the user "your message matches
     * skill X which is bound to agent Y, not your current agent Z".
     */
    List<SkillDefinitionEntity> findByEnabledTrueAndTriggerKeywordsIsNotNull();
}
