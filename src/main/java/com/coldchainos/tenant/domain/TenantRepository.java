package com.coldchainos.tenant.domain;

import com.coldchainos.shared.domain.TenantId;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(TenantId id);

    List<Tenant> findAll();
}
