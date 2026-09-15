package com.coldchainos.shared.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strongly typed Value Object representing a Tenant identifier.
 * Enforces slug naming invariants to ensure safe PostgreSQL schema names (e.g. tenant_pharma_a).
 */
public record TenantId(String value) {

    private static final Pattern VALID_SLUG = Pattern.compile("^[a-z0-9_]{3,32}$");

    public TenantId {
        Objects.requireNonNull(value, "TenantId cannot be null");
        String normalized = value.trim().toLowerCase();
        if (!VALID_SLUG.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "TenantId must be 3-32 characters of lowercase alphanumeric or underscore: " + value
            );
        }
        value = normalized;
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    /**
     * Resolves the corresponding PostgreSQL schema name.
     */
    public String toSchemaName() {
        return "tenant_" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
