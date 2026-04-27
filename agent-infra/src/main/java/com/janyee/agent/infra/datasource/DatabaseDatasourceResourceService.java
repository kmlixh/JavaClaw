package com.janyee.agent.infra.datasource;

import com.janyee.agent.infra.persistence.entity.DbDatasourceEntity;
import com.janyee.agent.infra.persistence.repository.DbDatasourceRepository;
import com.janyee.agent.runtime.datasource.DatasourceResource;
import com.janyee.agent.runtime.datasource.DatasourceResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DatabaseDatasourceResourceService implements DatasourceResourceService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDatasourceResourceService.class);

    private final DbDatasourceRepository repository;

    public DatabaseDatasourceResourceService(DbDatasourceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DatasourceResource> findByJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Optional.empty();
        }
        return repository.findByJdbcUrlAndEnabledTrue(jdbcUrl.trim()).map(this::toResource);
    }

    @Override
    public Optional<DatasourceResource> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findByIdAndEnabledTrue(id.trim()).map(this::toResource);
    }

    @Override
    public Optional<DatasourceResource> findDefault() {
        List<DbDatasourceEntity> enabled = repository.findAllByEnabledTrueOrderByIdAsc();
        if (enabled.isEmpty()) {
            return Optional.empty();
        }
        DbDatasourceEntity picked = enabled.get(0);
        if (enabled.size() > 1) {
            // Multiple enabled rows exist with no explicit "is_default" flag in schema.
            // Picking the lexicographically-smallest id is deterministic but arbitrary.
            // Surface a warning so operators know to disambiguate (either disable extras or add
            // an is_default column — the latter is a future migration).
            log.warn("datasource.default.ambiguous picked={}, enabledCount={}, all={}",
                    picked.getId(), enabled.size(),
                    enabled.stream().map(DbDatasourceEntity::getId).toList());
        }
        return Optional.of(toResource(picked));
    }

    private DatasourceResource toResource(DbDatasourceEntity entity) {
        return new DatasourceResource(
                entity.getId(),
                entity.getDisplayName(),
                entity.getJdbcUrl(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getDialect()
        );
    }
}
