package com.janyee.agent.infra.persistence.entity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** 用户"收藏"非自己所有的 SYSTEM/TENANT agent,出现在个人 agent 下拉里。 */
@Entity
@Table(name = "user_agent_binding")
@IdClass(UserAgentBindingEntity.Pk.class)
public class UserAgentBindingEntity {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Id
    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public static class Pk implements Serializable {
        private String userId;
        private String agentId;
        public Pk() {}
        public Pk(String userId, String agentId) {
            this.userId = userId;
            this.agentId = agentId;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(userId, pk.userId) && Objects.equals(agentId, pk.agentId);
        }
        @Override
        public int hashCode() { return Objects.hash(userId, agentId); }
    }
}
