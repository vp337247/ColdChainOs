package com.coldchainos.shared.multitenancy;

import com.coldchainos.shared.domain.TenantId;

import java.util.List;

/**
 * Port for querying active tenants without coupling shared infrastructure to the tenant domain module.
 * Adheres to the Dependency Inversion Principle (DIP).
 */
public interface TenantProvider {

    List<TenantId> getActiveTenantIds();
}
