package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
}
