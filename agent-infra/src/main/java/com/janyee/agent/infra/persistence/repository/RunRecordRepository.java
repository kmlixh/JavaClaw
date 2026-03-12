package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRecordRepository extends JpaRepository<RunRecordEntity, String> {
}
