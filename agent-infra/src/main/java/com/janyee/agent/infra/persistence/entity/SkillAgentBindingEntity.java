package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 桥表:一条 {@code skill_definition} 可以绑定多个 agent,反过来一个 agent 也可以
 * 挂多个 skill。V19 把原 {@code skill_definition.agent_id} 字段的语义搬到了这里,
 * 那个列在 V19 后退化为 legacy 冗余(可 NULL)。
 */
@Entity
@Table(name = "skill_agent_binding")
@IdClass(SkillAgentBindingEntity.PK.class)
public class SkillAgentBindingEntity {

    @Id
    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    @Id
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SkillAgentBindingEntity() {
    }

    public SkillAgentBindingEntity(String skillId, String agentId) {
        this.skillId = skillId;
        this.agentId = agentId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Instant getCreatedAt() { return createdAt; }

    public static class PK implements Serializable {
        private String skillId;
        private String agentId;

        public PK() {
        }

        public PK(String skillId, String agentId) {
            this.skillId = skillId;
            this.agentId = agentId;
        }

        public String getSkillId() { return skillId; }
        public void setSkillId(String skillId) { this.skillId = skillId; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK other)) return false;
            return Objects.equals(skillId, other.skillId) && Objects.equals(agentId, other.agentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skillId, agentId);
        }
    }
}
