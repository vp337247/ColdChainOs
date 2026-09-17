package com.coldchainos.shared.multitenancy;

import com.coldchainos.shared.domain.TenantId;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Manages the current Tenant context for the active execution thread.
 * Designed to prevent cross-tenant data leakage by strictly binding tenant context.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT_TENANT = new ThreadLocal<>();

    public static final String DEFAULT_TENANT_ID = "public";

    private TenantContext() {
        // Utility class
    }

    public static void setTenantId(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        CURRENT_TENANT.set(tenantId);
    }

    public static void setTenantId(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank() || DEFAULT_TENANT_ID.equalsIgnoreCase(tenantSlug)) {
            clear();
        } else {
            setTenantId(TenantId.of(tenantSlug));
        }
    }

    public static TenantId getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static String getCurrentSchema() {
        TenantId current = CURRENT_TENANT.get();
        return current != null ? current.toSchemaName() : DEFAULT_TENANT_ID;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Executes an operation within the context of a specified tenant, ensuring clean restoration.
     */
    public static <T> T executeAs(TenantId tenantId, Supplier<T> operation) {
        TenantId previous = CURRENT_TENANT.get();
        try {
            setTenantId(tenantId);
            return operation.get();
        } finally {
            if (previous != null) {
                CURRENT_TENANT.set(previous);
            } else {
                clear();
            }
        }
    }

    public static void executeAs(TenantId tenantId, Runnable operation) {
        executeAs(tenantId, () -> {
            operation.run();
            return null;
        });
    }
}
