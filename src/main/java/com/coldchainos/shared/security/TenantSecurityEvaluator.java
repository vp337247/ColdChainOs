package com.coldchainos.shared.security;

import com.coldchainos.shared.domain.TenantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Spring Security SpEL evaluator for verifying multi-tenant authorization boundaries.
 * Enforces that callers can only access data belonging to their authenticated tenant.
 */
@Component("tenantSecurityEvaluator")
public class TenantSecurityEvaluator {

    /**
     * Verifies that the current authenticated user belongs to the specified tenant.
     */
    public boolean isTenant(TenantId tenantId) {
        if (tenantId == null) {
            return false;
        }
        return isTenant(tenantId.value());
    }

    /**
     * Verifies that the current authenticated user belongs to the specified tenant.
     */
    public boolean isTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (authentication.getPrincipal() instanceof ColdChainUserPrincipal principal) {
            return tenantId.equalsIgnoreCase(principal.getTenantId());
        }

        return false;
    }
}
