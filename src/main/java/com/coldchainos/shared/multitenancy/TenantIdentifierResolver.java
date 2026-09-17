package com.coldchainos.shared.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate CurrentTenantIdentifierResolver implementation.
 * Provides Hibernate with the current tenant schema name for the active session.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.getCurrentSchema();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
