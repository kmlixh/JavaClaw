package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfigEntity, String> {
    List<LlmProviderConfigEntity> findByEnabledTrueOrderByDisplayNameAsc();

    Optional<LlmProviderConfigEntity> findFirstByEnabledTrueAndDefaultConfigTrueOrderByDisplayNameAsc();

    Optional<LlmProviderConfigEntity> findByIdAndEnabledTrue(String id);
}
