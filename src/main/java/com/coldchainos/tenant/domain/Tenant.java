package com.coldchainos.tenant.domain;

import com.coldchainos.shared.domain.AggregateRoot;
import com.coldchainos.shared.domain.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Tenant Aggregate Root representing an isolated customer organization.
 */
public class Tenant extends AggregateRoot<TenantId> {

    private final TenantId id;
    private final String organizationName;
    private TenantStatus status;
    private final Instant createdAt;

    public Tenant(TenantId id, String organizationName, TenantStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "TenantId cannot be null");
        this.organizationName = Objects.requireNonNull(organizationName, "organizationName cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    public static Tenant create(TenantId id, String organizationName) {
        return new Tenant(id, organizationName, TenantStatus.ACTIVE, Instant.now());
    }

    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
    }

    @Override
    public TenantId getId() {
        return id;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
