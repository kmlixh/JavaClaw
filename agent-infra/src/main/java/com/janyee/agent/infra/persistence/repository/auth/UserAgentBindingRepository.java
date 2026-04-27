package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.UserAgentBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAgentBindingRepository extends JpaRepository<UserAgentBindingEntity, UserAgentBindingEntity.Pk> {
    List<UserAgentBindingEntity> findByUserId(String userId);
    void deleteByUserIdAndAgentId(String userId, String agentId);
}
