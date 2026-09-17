package com.coldchainos.tenant.infrastructure.persistence;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.tenant.domain.Tenant;
import com.coldchainos.tenant.domain.TenantRepository;
import com.coldchainos.tenant.domain.TenantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PostgresTenantRepositoryAdapter implements TenantRepository, com.coldchainos.shared.multitenancy.TenantProvider {

    private final SpringDataTenantRepository springDataRepository;

    @Override
    @Transactional
    public Tenant save(Tenant tenant) {
        TenantJpaEntity entity = new TenantJpaEntity();
        entity.setId(tenant.getId().value());
        entity.setOrganizationName(tenant.getOrganizationName());
        entity.setSchemaName(tenant.getId().toSchemaName());
        entity.setStatus(tenant.getStatus().name());
        entity.setCreatedAt(tenant.getCreatedAt());

        TenantJpaEntity saved = springDataRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findById(TenantId id) {
        return springDataRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tenant> findAll() {
        return springDataRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantId> getActiveTenantIds() {
        return springDataRepository.findAll().stream()
            .filter(t -> "ACTIVE".equals(t.getStatus()))
            .map(t -> TenantId.of(t.getId()))
            .toList();
    }

    private Tenant toDomain(TenantJpaEntity entity) {
        return new Tenant(
            TenantId.of(entity.getId()),
            entity.getOrganizationName(),
            TenantStatus.valueOf(entity.getStatus()),
            entity.getCreatedAt()
        );
    }
}
