package com.janyee.agent.infra.persistence.repository.auth;

import com.janyee.agent.infra.persistence.entity.auth.OauthAuthorizationCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OauthAuthorizationCodeRepository extends JpaRepository<OauthAuthorizationCodeEntity, String> {
}
