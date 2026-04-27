package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.DbDatasourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DbDatasourceRepository extends JpaRepository<DbDatasourceEntity, String> {
    Optional<DbDatasourceEntity> findByJdbcUrlAndEnabledTrue(String jdbcUrl);
    Optional<DbDatasourceEntity> findByIdAndEnabledTrue(String id);
    List<DbDatasourceEntity> findAllByEnabledTrueOrderByIdAsc();
}
