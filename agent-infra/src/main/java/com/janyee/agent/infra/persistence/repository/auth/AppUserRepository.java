package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, String> {
    Optional<AppUserEntity> findByUsername(String username);
    Optional<AppUserEntity> findByEmail(String email);
}
